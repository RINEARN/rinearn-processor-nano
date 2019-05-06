/*
 * Copyright(C) 2019 RINEARN (Fumihiro Matsui)
 * This software is released under the MIT License.
 */

package com.rinearn.processornano.presenter;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.SwingUtilities;

import com.rinearn.processornano.model.AsynchronousScriptListener;
import com.rinearn.processornano.model.CalculatorModel;
import com.rinearn.processornano.spec.SettingContainer;
import com.rinearn.processornano.view.ViewContainer;

public final class RunButtonListener implements ActionListener {

	private CalculatorModel calculator = null;
	private ViewContainer view = null;
	private SettingContainer setting = null;

	public RunButtonListener(ViewContainer view, CalculatorModel calculator, SettingContainer setting) {
		this.calculator = calculator;
		this.view = view;
		this.setting = setting;
	}

	@Override
	public final void actionPerformed(ActionEvent e) {
		AsynchronousScriptListener scriptListener = new AsynchronousScriptListener() {
			public void scriptingFinished(String outputText) {
				SwingUtilities.invokeLater(new OutputFieldUpdater(view, outputText));
			}
		};
		calculator.calculateAsynchronously(this.view.inputField.getText(), this.setting, scriptListener);
	}
}