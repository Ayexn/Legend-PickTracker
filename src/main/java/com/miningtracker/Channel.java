package com.miningtracker;

import net.minecraft.world.BossEvent;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the player is on 띵타이쿤: the bar at the top right (a boss bar) shows the date, the area and the channel,
 * "2026.10.31 03:19 무성한 동굴 〈야생5〉". The channel is read from it ("야생 5", "마을 14", "아일랜드 4"). Only
 * read; nothing is sent to the server.
 */
final class Channel {
	private static final String WORDS = "(야생|마을|스폰|광산|아일랜드|채널)";
	/** The channel in its brackets, "〈야생5〉": the area name before it may hold the same words ("형광 광산"). */
	private static final Pattern BRACKETED = Pattern.compile("[〈<‹《\\[]\\s*" + WORDS + "\\s*(\\d{0,3})\\s*[〉>›》\\]]");
	/** Without brackets: a place word, maybe a number, not part of a longer word ("야생에서" is not a channel). */
	private static final Pattern LABEL = Pattern.compile("(?<![가-힣])" + WORDS + "\\s*(\\d{0,3})(?![가-힣])");

	private Channel() {
	}

	/** The channel named in one of the boss bars ("야생 5", "스폰"), or "". Bracketed names first. */
	static String in(Collection<? extends BossEvent> bars) {
		String loose = "";
		for (BossEvent bar : bars) {
			String text = StorageNotice.clean(bar.getName().getString());
			String bracketed = find(BRACKETED, text);
			if (!bracketed.isEmpty()) {
				return bracketed;
			}
			if (loose.isEmpty()) {
				loose = find(LABEL, text);
			}
		}
		return loose;
	}

	/** The channel in a boss bar's text, or "". */
	static String of(String text) {
		String bracketed = find(BRACKETED, text);
		return bracketed.isEmpty() ? find(LABEL, text) : bracketed;
	}

	private static String find(Pattern pattern, String text) {
		Matcher m = pattern.matcher(text);
		if (!m.find()) {
			return "";
		}
		return m.group(2).isEmpty() ? m.group(1) : m.group(1) + " " + m.group(2);
	}

	/** A 야생 channel: where mining is recorded. */
	static boolean isWild(String channel) {
		return channel.startsWith("야생");
	}
}
