package com.miningtracker;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What 띵타이쿤's item storage shows above the hotbar when it takes something the player picked up:
 * {@code [아이템 저장소] 다이아몬드 1개 수납! (270/30,000)} (the storage now holds 270 of it, at most 30,000), and
 * {@code ... N개 인출} when the player takes things out. Only read; nothing is sent to the server.
 */
final class StorageNotice {
	/**
	 * "[아이템 저장소] 다이아몬드 1개 수납! (270/30,000)", and the same said a little differently: something before the
	 * tag (an icon), no space before the number ("철원석3개"), "x3", "1개를 수납했습니다", no count in brackets.
	 */
	private static final Pattern STORED = Pattern.compile(
			"\\[아이템\\s*저장소\\]\\s*(.+?)(?:\\s+[xX×]\\s*|\\s*)(\\d[\\d,]*)\\s*개?\\s*[을를]?\\s*수납[^(\\d]*"
					+ "(?:\\(\\s*(\\d[\\d,]*)\\s*/\\s*(\\d[\\d,]*)\\s*\\))?");
	private static final Pattern AMOUNT = Pattern.compile("(\\d[\\d,]*)\\s*개");
	private static final Pattern FORMATTING = Pattern.compile("§.");

	/** "N개 수납": {@code balance} and {@code max} are -1 when the notice does not say them. */
	record Stored(String name, int amount, long balance, long max) {
	}

	private StorageNotice() {
	}

	/** The text without color codes and outer spaces. */
	static String clean(String text) {
		return FORMATTING.matcher(text).replaceAll("").strip();
	}

	/** A notice from the item storage at all (something like an icon may come before the tag). */
	static boolean isStorage(String text) {
		return text.replace(" ", "").contains("[아이템저장소]");
	}

	/** "... N개 수납!": what went into the storage, or null. */
	static Stored stored(String text) {
		Matcher m = STORED.matcher(text);
		if (!m.find() || text.contains("인출")) {
			return null;
		}
		long amount = number(m.group(2));
		if (amount <= 0 || amount > 1_000_000) {
			return null;
		}
		long balance = m.group(3) == null ? -1 : number(m.group(3));
		long max = m.group(4) == null ? -1 : number(m.group(4));
		return new Stored(m.group(1).strip(), (int) amount, balance, max);
	}

	/** "... N개 인출": how many were taken out of the storage, or 0. */
	static int takenOut(String text) {
		if (!isStorage(text) || !text.contains("인출")) {
			return 0;
		}
		Matcher m = AMOUNT.matcher(text);
		return m.find() ? (int) Math.max(0, Math.min(number(m.group(1)), 1_000_000)) : 0;
	}

	private static long number(String digits) {
		try {
			return Long.parseLong(digits.replace(",", ""));
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
