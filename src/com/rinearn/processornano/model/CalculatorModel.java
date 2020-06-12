/*
 * Copyright(C) 2019-2020 RINEARN (Fumihiro Matsui)
 * This software is released under the MIT License.
 */

package com.rinearn.processornano.model;

import java.io.File;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import com.rinearn.processornano.RinearnProcessorNanoException;
import com.rinearn.processornano.util.LocaleCode;
import com.rinearn.processornano.util.MessageManager;
import com.rinearn.processornano.util.ScriptFileLoader;
import com.rinearn.processornano.util.SettingContainer;

public final class CalculatorModel {

	private static final String SCRIPT_EXTENSION = ".vnano";
	private static final String DEFAULT_SCRIPT_ENCODING = "UTF-8";
	private static final String DEFAULT_FILE_IO_ENCODING = "UTF-8";

	private ScriptEngine engine = null; // 計算式やライブラリの処理を実行するためのVnanoのスクリプトエンジン
	private String dirPath = ".";

	private volatile boolean calculating = false;

	// スクリプト内から下記の組み込み関数「 output 」を呼んで渡した値を控えておくフィールド（GUIモードでの表示用）
	private String lastOutputContent = null;

	// スクリプトエンジンに組み込み関数「 output 」を提供するプラグインクラス
	public class OutputPlugin {

		private boolean isGuiMode;
		private SettingContainer setting;
		public OutputPlugin(SettingContainer setting, boolean isGuiMode) {
			this.setting = setting;
			this.isGuiMode = isGuiMode;
		}

		public void output(String value) {

			// GUIモード用に値を控える
			CalculatorModel.this.lastOutputContent = value;

			// CUIモード用に値を標準出力に出力する
			if (!this.isGuiMode) {
				System.out.println(value);
			}
		}
		public void output(long value) {
			this.output( Long.toString(value) );
		}
		public void output(double value) {

			// 設定内容に応じて丸め、書式を調整
			if ( !((Double)value).isNaN() && !((Double)value).isInfinite() ) {
				BigDecimal roundedValue = OutputValueFormatter.round( ((Double)value).doubleValue(), this.setting);
				String simplifiedValue = OutputValueFormatter.simplify( roundedValue );
				this.output(simplifiedValue);

			} else {
				this.output( Double.toString(value) );
			}
		}
		public void output(boolean value) {
			this.output( Boolean.toString(value) );
		}
	}


	// AsynchronousCalculationRunner から参照する
	public final boolean isCalculating() {
		return this.calculating;
	}

	// 初期化処理
	public final void initialize(
			SettingContainer setting, boolean isGuiMode, String dirPath, String libraryListFilePath, String pluginListFilePath)
					throws RinearnProcessorNanoException {

		this.dirPath = dirPath;

		// 式やライブラリの解釈/実行用に、Vnanoのスクリプトエンジンを読み込んで生成
		ScriptEngineManager manager = new ScriptEngineManager();
		this.engine = manager.getEngineByName("vnano");
		if (engine == null) {
			if (setting.localeCode.equals(LocaleCode.EN_US)) {
				MessageManager.showErrorMessage("Please put Vnano.jar in the same directory as RinearnProcessorNano.jar.", "Engine Loading Error");
			}
			if (setting.localeCode.equals(LocaleCode.JA_JP)) {
				MessageManager.showErrorMessage("Vnano.jar を RinearnProcessorNano.jar と同じフォルダ内に配置してください。", "エンジン読み込みエラー");
			}
			throw new RinearnProcessorNanoException("ScriptEngine of the Vnano could not be loaded.");
		}

		// ライブラリ/プラグインの読み込みリストファイルを登録
		try {
			this.engine.put("___VNANO_LIBRARY_LIST_FILE", libraryListFilePath);
			this.engine.put("___VNANO_PLUGIN_LIST_FILE", pluginListFilePath);

		// 読み込みに失敗しても、そのプラグイン/ライブラリ以外の機能には支障が無いため、本体側は落とさない。
		// そのため、例外をさらに上には投げない。（ただし失敗メッセージは表示する。）
		} catch (Exception e) {
			String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
			if (setting.localeCode.equals(LocaleCode.EN_US)) {
				MessageManager.showErrorMessage(message, "Plug-in/Library Loading Error");
			}
			if (setting.localeCode.equals(LocaleCode.JA_JP)) {
				MessageManager.showErrorMessage(message, "プラグイン/ライブラリ 読み込みエラー");
			}
			// プラグインエラーはスクリプトの構文エラーよりも深いエラーなため、常にスタックトレースを出力する
			System.err.println("\n" + message);
			MessageManager.showExceptionStackTrace(e);
		}

		// 組み込み関数「 output 」を提供するプラグイン（このクラス内に内部クラスとして実装）を登録
		this.engine.put("OutputPlugin", new CalculatorModel.OutputPlugin(setting, isGuiMode));
	}


	// 終了時処理
	public void shutdown(SettingContainer setting) {
		try {
			// プラグインを接続解除し、ライブラリ登録も削除
			this.engine.put("___VNANO_COMMAND", "REMOVE_PLUGIN");
			this.engine.put("___VNANO_COMMAND", "REMOVE_LIBRARY");

		// shutdown に失敗しても上層ではどうしようも無いため、ここで通知し、さらに上には投げない。
		} catch (Exception e) {
			String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
			if (setting.localeCode.equals(LocaleCode.EN_US)) {
				MessageManager.showErrorMessage(message, "Plug-in Finalization Error");
			}
			if (setting.localeCode.equals(LocaleCode.JA_JP)) {
				MessageManager.showErrorMessage(message, "プラグイン終了時処理エラー");
			}
			// プラグインエラーはスクリプトの構文エラーよりも深いエラーなため、常にスタックトレースを出力する
			System.err.println("\n" + message);
			MessageManager.showExceptionStackTrace(e);
		}
	}


	// CUIモードでは RinearnProcessorNano.calculate、GUIモードでは AsynchronousCalculationListener.run から呼ばれて実行される
	public final synchronized String calculate(String inputtedContent, boolean isGuiMode, SettingContainer setting)
			throws ScriptException, RinearnProcessorNanoException {

		// 計算中の状態にする（AsynchronousCalculationRunner から参照する）
		this.calculating = true;

		// スクリプト内から output 関数に渡した内容を控える変数をクリア
		this.lastOutputContent = null;

		// 入力内容がスクリプトかどうか、およびスクリプト名を控える
		boolean scriptFileInputted = false;  // スクリプトの場合は true, 計算式の場合は false
		File scriptFile = null;

		// 入力内容がスクリプトの拡張子で終わっている場合は、実行対象スクリプトファイルのパスと見なす
		if (inputtedContent.endsWith(SCRIPT_EXTENSION)) {

			scriptFileInputted = true;
			scriptFile = new File(inputtedContent);

			// 指定内容がフルパスでなかった場合は、dirPath のディレクトリ基準の相対パスと見なす
			if (!scriptFile.isAbsolute()) {
				scriptFile = new File(dirPath, scriptFile.getPath());
			}

			// 入力内容をスクリプトファイルの内容で置き換え
			try {
				inputtedContent = ScriptFileLoader.load(scriptFile.getAbsolutePath(), DEFAULT_SCRIPT_ENCODING, setting);
			} catch (RinearnProcessorNanoException e) {
				this.calculating = false;
				throw e;
			}

			// 設定の一部をスクリプト用に書き換え（整数をfloatと見なすオプションなどは、式の計算には良くても、スクリプトの場合は不便なので）
			try {
				setting = setting.clone();
				setting.evalNumberAsFloat = false;
				setting.evalOnlyFloat = false;
				setting.evalOnlyExpression = false;
			} catch (CloneNotSupportedException e) {
				this.calculating = false;
				throw new RinearnProcessorNanoException(e);
			}

		// それ以外は計算式と見なす
		} else {

			// 式の記述内容を設定に応じて正規化（全角を半角にしたりなど）
			if (setting.inputNormalizerEnabled) {
				inputtedContent = Normalizer.normalize(inputtedContent, Normalizer.Form.NFKC);
			}

			// 末尾にセミコロンを追加（無い場合のみ）
			if (!inputtedContent.trim().endsWith(";")) {
				inputtedContent += ";";
			}
		}


		// ライブラリ/プラグインの再読み込み
		try {
			this.engine.put("___VNANO_COMMAND", "RELOAD_LIBRARY");
			this.engine.put("___VNANO_COMMAND", "RELOAD_PLUGIN");

		// 読み込みに失敗しても、そのプラグイン/ライブラリ以外の機能には支障が無いため、本体側は落とさない。
		// そのため、例外をさらに上には投げない。（ただし失敗メッセージは表示する。）
		} catch (Exception e) {
			String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
			if (setting.localeCode.equals(LocaleCode.EN_US)) {
				MessageManager.showErrorMessage(message, "Plug-in/Library Loading Error");
			}
			if (setting.localeCode.equals(LocaleCode.JA_JP)) {
				MessageManager.showErrorMessage(message, "プラグイン/ライブラリ 読み込みエラー");
			}
			// プラグインエラーはスクリプトの構文エラーよりも深いエラーなため、常にスタックトレースを出力する
			System.err.println("\n" + message);
			MessageManager.showExceptionStackTrace(e);
		}


		// スクリプトエンジン関連の設定値を Map（オプションマップ）に格納し、エンジンに渡して設定
		Map<String, Object> optionMap = new HashMap<String, Object>();
		optionMap.put("ACCELERATOR_ENABLED", setting.acceleratorEnabled);
		optionMap.put("EVAL_NUMBER_AS_FLOAT", setting.evalNumberAsFloat);
		optionMap.put("EVAL_ONLY_FLOAT", setting.evalOnlyFloat);
		optionMap.put("EVAL_ONLY_EXPRESSION", setting.evalOnlyExpression);
		optionMap.put("LOCALE", LocaleCode.toLocale(setting.localeCode));
		optionMap.put("DUMPER_ENABLED", setting.dumperEnabled);
		optionMap.put("DUMPER_TARGET", setting.dumperTarget);
		optionMap.put("UI_MODE", isGuiMode ? "GUI" : "CUI");
		optionMap.put("FILE_IO_ENCODING", DEFAULT_FILE_IO_ENCODING);
		if (scriptFileInputted) {
			optionMap.put("MAIN_SCRIPT_NAME", scriptFile.getName());
			optionMap.put("MAIN_DIRECTORY_PATH", scriptFile.getParentFile().getAbsolutePath());
		}
		engine.put("___VNANO_OPTION_MAP", optionMap);


		// スクリプトエンジンで計算処理を実行
		Object value = null;
		try {
			value = this.engine.eval(inputtedContent);

		// 入力した式やライブラリに誤りがあった場合は、計算終了状態に戻してから例外を上層に投げる
		} catch (ScriptException e) {
			this.calculating = false;
			throw e;
		}

		// このメソッドの戻り値（出力フィールドに表示される文字列）を格納する
		String outputText = null;

		// スクリプトファイルを実行した場合は、スクリプト内から組み込み関数「 output 」を呼んで渡した内容が
		// このクラスの lastOutputContent フィールドに保持されている（内部クラス OutputPlugin 参照）ので、
		// GUIモードではそれを出力フィールドに表示するために返す。
		// CUIモードでは逐次的に標準出力に出力済みなのでもう何も追加出力する必要は無く、従って何も返さない。
		if (scriptFileInputted) {
			if (isGuiMode) {
				outputText = this.lastOutputContent;
			}

		// 計算式を実行した場合は、その式の値を丸めた上で文字列化して出力する
		} else {
			if (value instanceof Double) {
				if ( !((Double)value).isNaN() && !((Double)value).isInfinite() ) {
					value = OutputValueFormatter.round( ((Double)value).doubleValue(), setting); // 丸め処理： 結果は BigDecimal
					value = OutputValueFormatter.simplify( (BigDecimal)value );                  // 書式調整： 結果は String
				}
			}
			if (value != null) {
				value = value.toString();
			}
			outputText = (String)value;
		}

		// 計算終了状態に戻す（AsynchronousCalculationRunner から参照する）
		this.calculating = false;

		return outputText;
	}


	public final synchronized void calculateAsynchronously(
			String inputExpression, SettingContainer setting, AsynchronousCalculationListener scriptListener) {

		// 計算実行スレッドを生成して実行（中でこのクラスの calculate が呼ばれて実行される）
		AsynchronousCalculationRunner asyncCalcRunner
				= new AsynchronousCalculationRunner(inputExpression, scriptListener, this, setting);

		Thread calculatingThread = new Thread(asyncCalcRunner);
		calculatingThread.start();
	}

}
