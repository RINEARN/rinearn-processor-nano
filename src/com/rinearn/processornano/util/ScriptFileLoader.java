/*
 * Copyright(C) 2020 RINEARN (Fumihiro Matsui)
 * This software is released under the MIT License.
 */

package com.rinearn.processornano.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import com.rinearn.processornano.RinearnProcessorNanoException;

public class ScriptFileLoader {

	public static final String load(
			String scriptFilePath, String dirPath, String defaultEncoding, SettingContainer setting)
					throws RinearnProcessorNanoException {

		File file = new File(scriptFilePath);

		// 指定内容がフルパスでなかった場合は、dirPath のディレクトリ基準の相対パスと見なす
		if (!file.isAbsolute()) {
			file = new File(dirPath, scriptFilePath);
		}

		// ファイルが存在しない場合はエラー
		if (!file.exists()) {
			if (setting.localeCode.equals(LocaleCode.EN_US)) {
				throw new RinearnProcessorNanoException("The script file not found: " + file.getPath());
			}
			if (setting.localeCode.equals(LocaleCode.JA_JP)) {
				throw new RinearnProcessorNanoException("スクリプトファイルが見つかりません: " + file.getPath());
			}
		}

		// I/Oに関する読み込み失敗のエラーハンドリングは、後でまとめて行うため、このフラグに成功/失敗を控える
		boolean loadingFailed = false;
		String loadingFailedCauseInfo = "";

		// スクリプト先頭行に文字コード宣言があれば、まずそれを読む
		String firstLine = "";
		Charset charset = Charset.forName(defaultEncoding);
		try (BufferedReader bufferedReader = new BufferedReader( new InputStreamReader( new FileInputStream(file.getPath()), charset) ) ) {
			firstLine = bufferedReader.readLine();
		} catch (IOException ioe) {
			loadingFailed = true;
			loadingFailedCauseInfo = ioe.getClass().getCanonicalName() + ": " + ioe.getMessage();
		}

		// 先頭行の中の空白を詰める
		// （ \uFEFF は特殊空白の一種で通常は使われないが、UTF-8のBOMは上記処理ではこの文字のUTF-8表現と見なしてデコードされるので、それも詰める)
		firstLine = firstLine.replaceAll("\\t", "").replaceAll("\\s", "").replaceAll("\uFEFF", "");

		// 先頭行が文字コード宣言のキーワードで始まっていたら、宣言されている文字コード名を取得し、charset を再設定
		String[] encodingDeclHeads = { "coding", "#coding", "encoding", "#encoding", "encode", "#encode" };
		String declaredEncodingName = null;
		for (String encodingDeclHead: encodingDeclHeads) {
			if (firstLine.startsWith(encodingDeclHead) && 0 <= firstLine.indexOf(";")) {
				declaredEncodingName = firstLine.substring(encodingDeclHead.length(), firstLine.indexOf(";"));
				try {
					charset = Charset.forName(declaredEncodingName);
				} catch (IllegalCharsetNameException | UnsupportedCharsetException ce) {
					loadingFailed = true;
					loadingFailedCauseInfo = ce.getClass().getCanonicalName() + ": " + ce.getMessage();
				}
			}
		}

		// 再設定後の charset でファイル全体を読み込み
		List<String> lineList = null;
		try {
			lineList = Files.readAllLines(Paths.get(file.getPath()), charset);
		} catch (IOException ioe) {
			loadingFailed = true;
			loadingFailedCauseInfo = ioe.getClass().getCanonicalName() + ": " + ioe.getMessage();
		}
		String scriptCode = String.join("\n", lineList.toArray(new String[0]));

		// 読み込み過程でエラーが発生していた場合
		if (loadingFailed) {
			if (setting.localeCode.equals(LocaleCode.EN_US)) {
				throw new RinearnProcessorNanoException(
					"The script file could not be loaded: " + file.getPath()
					+ "\n\n" +
					"(Cause: " + loadingFailedCauseInfo + ")"
				);
			}
			if (setting.localeCode.equals(LocaleCode.JA_JP)) {
				throw new RinearnProcessorNanoException(
					"スクリプトファイルの読み込みに失敗しました： " + file.getPath()
					+ "\n\n" +
					"(原因: " + loadingFailedCauseInfo + ")"
				);
			}
		}

		return scriptCode;
	}
}
