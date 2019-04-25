/*
 * Copyright(C) 2019 RINEARN (Fumihiro Matsui)
 * This software is released under the MIT License.
 */

package com.rinearn.processornano;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import javax.swing.SwingUtilities;

import com.rinearn.processornano.calculator.Calculator;
import com.rinearn.processornano.event.EventListenerManager;
import com.rinearn.processornano.spec.LocaleCode;
import com.rinearn.processornano.spec.SettingContainer;
import com.rinearn.processornano.ui.MessageManager;
import com.rinearn.processornano.ui.UIContainer;
import com.rinearn.processornano.ui.UIDisposer;
import com.rinearn.processornano.ui.UIInitializer;


public final class RinearnProcessorNano {

	public RinearnProcessorNano() {
		// 起動は、インスタンス生成後に明示的に launchCalculatorWindow() を呼ぶ
	}


	public final void launchCalculatorWindow() {

		// 計算機、電卓画面UIコンテナ、設定値コンテナのインスタンスを生成
		Calculator calculator = new Calculator();
		UIContainer ui = new UIContainer();
		SettingContainer setting = new SettingContainer();


		// 設定スクリプトを実行して設定値コンテナを初期化
		try {
			// 設定スクリプトを読み込む
			String settingScriptCode = null;
			settingScriptCode = this.loadCode(
				SettingContainer.SETTING_SCRIPT_PATH, SettingContainer.SETTING_SCRIPT_ENCODING,
				LocaleCode.getDefaultLocaleCode()
			);

			// 設定スクリプトを実行して設定値を書き込む（スクリプトエンジンはメソッド内で生成）
			setting.evaluateSettingScript(settingScriptCode, SettingContainer.SETTING_SCRIPT_PATH);

		// 設定スクリプトの文法エラーなどで失敗した場合
		} catch (RinearnProcessorNanoException e) {
			e.printStackTrace();
			return;
		}


		// 計算機の初期化
		try {
			// ライブラリスクリプトを読み込む
			String libraryScriptCode = this.loadCode(
				setting.libraryScriptPath, setting.libraryScriptEncoding, setting.localeCode
			);

			// 計算機を初期化
			calculator.initialize(setting, libraryScriptCode);

		// スクリプトエンジンの接続や、ライブラリスクリプトの文法エラーなどで失敗した場合
		} catch (RinearnProcessorNanoException e) {
			e.printStackTrace();

			// UIリソースを破棄して終了
			try {
				SwingUtilities.invokeAndWait(new UIDisposer(ui));
				return;
			} catch (InvocationTargetException | InterruptedException disposeException) {
				disposeException.printStackTrace();
				System.exit(1);
			}
		}


		// 電卓画面UIを構築して初期化
		try {
			UIInitializer uiInitialiser = new UIInitializer(ui, setting); // 別スレッドで初期化するためのRunnable
			SwingUtilities.invokeAndWait(uiInitialiser);                  // それをSwingのイベントスレッドで実行
			EventListenerManager.addAllEventListenersToUI(ui, calculator, setting);  // イベントリスナを登録

		// 初期化実行スレッドの処理待ち時の割り込みで失敗した場合など（結構異常な場合なので、リトライせず終了する）
		} catch (InvocationTargetException | InterruptedException e) {

			if (setting.localeCode.equals(LocaleCode.EN_US)) {
				MessageManager.showMessage("Unexpected exception occurred: " + e.getClass().getCanonicalName(), "Error");
			}
			if (setting.localeCode.equals(LocaleCode.JA_JP)) {
				MessageManager.showMessage("予期しない例外が発生しました: " + e.getClass().getCanonicalName(), "エラー");
			}
			e.printStackTrace();
			return; // この例外が発生する場合はまだUI構築が走っていないので、破棄するUIリソースはない
		}
	}


	private final String loadCode(String filePath, String encoding, String localeCode)
			throws RinearnProcessorNanoException {

		// 指定されたファイルが存在するか検査
		if (!new File(filePath).exists()) {
			if (localeCode.equals(LocaleCode.EN_US)) {
				MessageManager.showMessage("The file \"" + filePath + "\" does not exist.", "Code Loading Error");
			}
			if (localeCode.equals(LocaleCode.JA_JP)) {
				MessageManager.showMessage("ファイル \"" + filePath + "\" が見つかりません。", "コード読み込みエラー");
			}
			throw new RinearnProcessorNanoException("The file \"" + filePath + "\" does not exist.");
		}

		// 読み込み処理
		try {

			// 全行を読み込み、行単位で格納したListを取得
			List<String> lines = Files.readAllLines(Paths.get(filePath), Charset.forName(encoding));

			// 改行コードを挟みつつ全行を結合
			StringBuilder codeBuilder = new StringBuilder();
			String eol = System.getProperty("line.separator");
			for (String line: lines) {
				codeBuilder.append(line);
				codeBuilder.append(eol);
			}
			String code = codeBuilder.toString();

			// UTF-8ではBOMの有無を検査し、付いている場合は削除
			if(encoding.toLowerCase().equals("utf-8")) {
				// UTF-8のBOMは 0xEF 0xBB 0xBF だが、文字列内部表現がUTF-16な都合で、読み込み後は 0xFEFF が付いている
				final char bom = (char)0xFEFF;
				if(0 < code.length() && code.charAt(0) == bom){
					code = code.substring(1);
				}
			}

			return code;

		// 非対応の文字コードが指定された場合
		} catch (UnsupportedCharsetException uce) {

			if (localeCode.equals(LocaleCode.EN_US)) {
				MessageManager.showMessage("The encoding \"" + encoding + "\" is not supported.", "Code Loading Error");
			}
			if (localeCode.equals(LocaleCode.JA_JP)) {
				MessageManager.showMessage("非対応の文字コード \"" + encoding + "\" が指定されています。", "コード読み込みエラー");
			}
			throw new RinearnProcessorNanoException(uce);

		// 何らかの理由で読み込みに失敗した場合
		} catch (IOException ioe) {

			if (localeCode.equals(LocaleCode.EN_US)) {
				MessageManager.showMessage(
					"An error (IOException) occurred for the loading of \"" + filePath + "\".",
					"Code Loading Error"
				);
			}
			if (localeCode.equals(LocaleCode.JA_JP)) {
				MessageManager.showMessage(
					"\"" + filePath + "\" の読み込みにおいて、エラー (IOException) が発生しました。",
					"コード読み込みエラー"
				);
			}
			throw new RinearnProcessorNanoException(ioe);
		}
	}

}
