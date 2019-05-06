/*
 * Copyright(C) 2019 RINEARN (Fumihiro Matsui)
 * This software is released under the MIT License.
 */

package com.rinearn.processornano.model;

import javax.script.ScriptException;

import com.rinearn.processornano.spec.LocaleCode;
import com.rinearn.processornano.spec.SettingContainer;
import com.rinearn.processornano.util.MessageManager;


public final class AsynchronousScriptRunner implements Runnable {

	private AsynchronousScriptListener scriptListener = null;
	private CalculatorModel calculator = null;
	private SettingContainer setting = null;
	private String inputExpression = null;

	public AsynchronousScriptRunner(
			String inputExpression, AsynchronousScriptListener scriptListener,
			CalculatorModel calculator, SettingContainer setting) {

		this.inputExpression = inputExpression;
		this.scriptListener = scriptListener;
		this.calculator = calculator;
		this.setting = setting;
	}

	@Override
	public final void run() {

		// スクリプト内容が重い場合に実行ボタンが連打されると、
		// 処理がどんどん積もっていって全部消化されるまで待たなければいけなくなるので、
		// 実行中に実行リクエストがあった場合はその場で弾くようにする。

		if (this.calculator.isRunning()) {
			if (setting.localeCode.equals(LocaleCode.EN_US)) {
				MessageManager.showErrorMessage("The previous calculation have not finished yet!", "!");
			}
			if (setting.localeCode.equals(LocaleCode.JA_JP)) {
				MessageManager.showErrorMessage("まだ前の計算を実行中です !", "!");
			}
			return;
		}

		this.calculator.setRunning(true);

		// 入力フィールドの計算式を実行し、結果の値を取得
		String outputText = "";
		try {
			outputText = this.calculator.calculate(this.inputExpression, this.setting);

		} catch (ScriptException e) {
			String errorMessage = MessageManager.customizeExceptionMessage(e.getMessage());
			if (setting.localeCode.equals(LocaleCode.EN_US)) {
				MessageManager.showErrorMessage(errorMessage, "Input/Library Error");
			}
			if (setting.localeCode.equals(LocaleCode.JA_JP)) {
				MessageManager.showErrorMessage(errorMessage, "計算式やライブラリのエラー");
			}
			e.printStackTrace();
			this.calculator.setRunning(false);
			return;
		}

		// 計算リクエスト元に計算完了を通知
		this.scriptListener.scriptingFinished(outputText);

		this.calculator.setRunning(false);
	}
}
