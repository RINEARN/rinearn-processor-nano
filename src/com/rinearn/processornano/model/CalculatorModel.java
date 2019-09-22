/*
 * Copyright(C) 2019 RINEARN (Fumihiro Matsui)
 * This software is released under the MIT License.
 */

package com.rinearn.processornano.model;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import com.rinearn.processornano.RinearnProcessorNanoException;
import com.rinearn.processornano.spec.LocaleCode;
import com.rinearn.processornano.spec.SettingContainer;
import com.rinearn.processornano.util.MessageManager;
import com.rinearn.processornano.util.PluginLoader;

public final class CalculatorModel {

	private static final String[] PLUGIN_BASE_PATHS = { "./plugin/" };

	private ScriptEngine engine = null; // 計算式やライブラリの処理を実行するためのVnanoのスクリプトエンジン
	private volatile boolean calculating = false;

	// AsynchronousCalculationRunner から参照する
	public final boolean isCalculating() {
		return this.calculating;
	}

	public final void initialize(SettingContainer setting, String[] libraryScripts, String[] libraryScriptNames)
					throws RinearnProcessorNanoException {

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

		// スクリプトエンジンに渡すオプション値マップを用意
		Map<String, Object> optionMap = new HashMap<String, Object>();
		optionMap.put("ACCELERATOR_ENABLED", setting.acceleratorEnabled);
		optionMap.put("EVAL_NUMBER_AS_FLOAT", setting.evalNumberAsFloat);
		optionMap.put("LIBRARY_SCRIPTS", libraryScripts);
		optionMap.put("LIBRARY_SCRIPT_NAMES", libraryScriptNames);
		optionMap.put("LOCALE", LocaleCode.toLocale(setting.localeCode));
		optionMap.put("DUMPER_ENABLED", setting.dumperEnabled);
		optionMap.put("DUMPER_TARGET", setting.dumperTarget);

		// スクリプトエンジンにオプションマップを設定
		engine.put("___VNANO_OPTION_MAP", optionMap);

		// プラグインを読み込んでスクリプトエンジンに接続
		PluginLoader pluginLoader = new PluginLoader(setting.localeCode);
		pluginLoader.open(PLUGIN_BASE_PATHS);
		for (String pluginPath: setting.pluginPaths) {
			try {

				Object plugin = pluginLoader.loadPlugin(pluginPath);
				engine.put("___VNANO_AUTO_KEY", plugin);

			} catch (RinearnProcessorNanoException e) {
				e.printStackTrace();
				// 接続に失敗しても、そのプラグイン以外の機能には支障が無いため、本体側は落とさない。
				// そのため、例外をさらに上には投げない。（ただし失敗メッセージは表示する。）
			}
		}
	}


	public final synchronized String calculate(String inputExpression, SettingContainer setting)
			throws ScriptException {

		// 計算中の状態にする（AsynchronousCalculationRunner から参照する）
		this.calculating = true;

		// 設定に応じて、まず入力フィールドの内容を正規化
		if (setting.inputNormalizerEnabled) {
			inputExpression = Normalizer.normalize(inputExpression, Normalizer.Form.NFKC);
		}

		// 入力された式を、式文のスクリプトにするため、末尾にセミコロンを追加（無い場合のみ）
		String inputScript = inputExpression;
		if (!inputScript.trim().endsWith(";")) {
			inputScript += ";";
		}

		// 計算を実行
		Object value = null;
		try {
			value = this.engine.eval(inputScript);

		// 入力した式やライブラリに誤りがあった場合は、計算終了状態に戻してから例外を上層に投げる
		} catch (ScriptException e) {
			this.calculating = false;
			throw e;
		}

		// 値が浮動小数点数なら、設定内容に応じて丸める
		if (value instanceof Double) {
			if ( !((Double)value).isNaN() && !((Double)value).isInfinite() ) {
				value = Rounder.round( ((Double)value).doubleValue(), setting); // 型は BigDecimal になる
			}
		}

		// 値を文字列化
		String outputText = "";
		if (value != null) {
			outputText = value.toString();
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
