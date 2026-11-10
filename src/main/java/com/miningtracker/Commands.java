package com.miningtracker;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Client-side commands (never sent to the server): /광질 ... */
public final class Commands {
	private Commands() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
		dispatcher.register(literal("광질")
				.executes(c -> help(c.getSource()))
				.then(literal("도움말").executes(c -> help(c.getSource())))
				.then(literal("설정").executes(c -> {
					Tracker.INSTANCE.requestSettings();
					return 1;
				}))
				.then(literal("시작").executes(c -> start(c.getSource())))
				.then(literal("종료").executes(c -> stop(c.getSource())))
				// the old names (0.1.1 "일시정지", up to 0.5.1 "끝") keep working
				.then(literal("끝").executes(c -> stop(c.getSource())))
				.then(literal("일시정지").executes(c -> stop(c.getSource())))
				.then(literal("초기화").executes(c -> {
					Tracker.INSTANCE.clearTallies();
					return reply(c.getSource(), "집계판을 초기화했어요. 지금까지 캔 건 광질 통계에 남아요.");
				}))
				.then(literal("숨기기").executes(c -> {
					ModConfig.get().hudVisible = false;
					ModConfig.save();
					return reply(c.getSource(), "집계판을 숨겼어요. 다시 보기: /광질 보이기");
				}))
				.then(literal("보이기").executes(c -> {
					ModConfig.get().hudVisible = true;
					ModConfig.save();
					return reply(c.getSource(), "집계판을 다시 보여줄게요. (기록 중일 때만 떠요)");
				}))
				.then(literal("알림")
						.then(literal("켜기").executes(c -> {
							ModConfig.get().toastsEnabled = true;
							ModConfig.save();
							return reply(c.getSource(), "획득 알림을 켰어요.");
						}))
						.then(literal("끄기").executes(c -> {
							ModConfig.get().toastsEnabled = false;
							ModConfig.save();
							return reply(c.getSource(), "획득 알림을 껐어요.");
						})))
				.then(literal("블록")
						.then(literal("켜기").executes(c -> {
							ModConfig.get().mineralBlocks = true;
							ModConfig.save();
							return reply(c.getSource(), "먹은 광물을 블록으로 보여줘요 (9개 = 블록 1개, 남는 건 +N). 끄기: /광질 블록 끄기");
						}))
						.then(literal("끄기").executes(c -> {
							ModConfig.get().mineralBlocks = false;
							ModConfig.save();
							return reply(c.getSource(), "먹은 광물을 개수로 보여줘요.");
						})))
				.then(literal("편집").executes(c -> {
					Tracker.INSTANCE.requestEditor();
					return 1;
				}))
				.then(literal("통계").executes(c -> {
					Tracker.INSTANCE.requestStats();
					return 1;
				}))
				.then(literal("진단").executes(c -> diagnose(c.getSource()))
						.then(literal("최근").executes(c -> recent(c.getSource())))
						.then(literal("광물").executes(c -> minerals(c.getSource())))));
	}

	private static int start(FabricClientCommandSource source) {
		Tracker tracker = Tracker.INSTANCE;
		if (tracker.isRecording()) {
			return reply(source, "이미 기록 중이에요. 끝내기: /광질 종료");
		}
		tracker.startRecording(true);
		ModConfig cfg = ModConfig.get();
		return reply(source, cfg.autoMode
				? "기록을 시작했어요. " + Tracker.formatSeconds(cfg.endAfterSeconds) + " 동안 안 캐면 끝나요."
				: "기록을 시작했어요. 끝내기: /광질 종료 (또는 F7)");
	}

	private static int stop(FabricClientCommandSource source) {
		Tracker tracker = Tracker.INSTANCE;
		if (!tracker.isRecording()) {
			return reply(source, "지금은 기록 중이 아니에요.");
		}
		boolean summary = ModConfig.get().chatSummary && !tracker.isEmpty();
		tracker.stopRecording(source.getClient(), true);
		if (!summary) {
			reply(source, "기록을 끝냈어요.");
		}
		return 1;
	}

	/** Shows the recording state, where the player is, and which detection paths fired. */
	private static int diagnose(FabricClientCommandSource source) {
		Tracker tracker = Tracker.INSTANCE;
		ModConfig cfg = ModConfig.get();
		List<String> lines = new ArrayList<>();
		lines.add("[픽트래커] 진단");
		String state = !tracker.isRecording() ? "기록 안 함"
				: tracker.isManualSession() ? "기록 중 (직접 시작)" : "기록 중 (자동으로 시작)";
		lines.add(" 상태: " + state + " · 방식: " + (cfg.autoMode ? "자동" : "수동")
				+ " · 끝 판정: " + Tracker.formatSeconds(cfg.endAfterSeconds));
		ClientLevel level = source.getClient().level;
		if (level != null) {
			String channel = tracker.channel();
			lines.add(channel.isEmpty()
					? " 채널: 보스바에서 못 찾음 → 월드 이름으로 판단 (" + Tracker.dimensionId(level) + ", "
							+ (Tracker.isWildWorld(level) ? "야생으로 봐요)" : "야생 아님)")
					: " 채널(보스바): " + channel + (Tracker.isWildWorld(level) ? " (야생으로 봐요)" : " (야생 아님)"));
			lines.add(" 이곳 자동 기록: " + (Tracker.isAutoPlace(level) ? "켜짐 (야생)" : "꺼짐 (야생 아님)"));
		}
		Tracker.Stats s = tracker.stats;
		lines.add(" 세는 방식: 저장소 알림" + (cfg.countInventory ? " + 인벤토리" : " (인벤토리는 설정에서 꺼짐)"));
		lines.add(" 저장소 알림으로 센 획득: " + s.storageNotices + "번 (" + s.storageItems + "개)"
				+ (s.storageFromScreen > 0 ? " · 그중 화면 글자로 읽은 것 " + s.storageFromScreen + "번" : "")
				+ (s.storageRepeats > 0 ? " · 같은 알림이라 뺀 것 " + s.storageRepeats + "번 (" + s.storageRepeatItems + "개)" : "")
				+ (s.storageNotRecording > 0 ? " · 기록 중이 아니거나 야생이 아니라 뺀 것 " + s.storageNotRecording + "번" : "")
				+ (s.storageUnread > 0 ? " · 못 읽은 저장소 글자 " + s.storageUnread + "번 (/광질 진단 최근)" : ""));
		if (s.storageGapFilled > 0 || s.storageGap > 0 || s.storageCapped > 0 || s.storageUnknown > 0 || s.withdrawals > 0) {
			lines.add(" 놓친 알림으로 채운 것: " + s.storageGapFilled + "개 · 저장소가 더 늘었지만 안 센 것: " + s.storageGap
					+ "개 · 알림보다 저장소가 덜 늘어서 뺀 것: " + s.storageCapped + "개 · 이름을 몰라 종이로 센 것: "
					+ s.storageUnknown + "번 · 인출: " + s.withdrawals + "번");
		}
		if (!s.gapWhy.isEmpty()) {
			List<String> why = new ArrayList<>();
			for (Map.Entry<String, Long> e : s.gapWhy.entrySet()) {
				why.add(e.getKey().replace("그 사이 ", "") + " " + e.getValue() + "개");
			}
			lines.add(" 안 센 이유: " + String.join(" · ", why));
		}
		InventoryWatch inv = tracker.inventoryWatch();
		lines.add(" 인벤토리로 바로 들어온 걸로 센 획득: " + inv.counted + "번 (" + inv.countedItems + "개)"
				+ (cfg.countInventory ? "" : " (설정에서 꺼짐)")
				+ (inv.skipped > 0 ? " · 획득이 아니라서 안 센 인벤토리 변화 " + inv.skipped + "번" : "")
				+ (inv.withdrawnItems > 0 ? " · 인출한 것 " + inv.withdrawnItems + "개" : ""));
		lines.add(" 주변 블록으로 센 블록(3x3 등): " + s.neighborBlocks + "개");
		lines.add(" 서버가 되돌린 블록: " + s.restoredBlocks + "개 (그중 서버가 바로 다시 깨서 다시 센 것 " + s.rebrokenBlocks + "개)");
		lines.add(" 광물별로 보기: /광질 진단 광물 · 무엇을 왜 세고 안 셌는지: /광질 진단 최근");
		for (String line : lines) {
			source.sendFeedback(Component.literal(line));
		}
		return 1;
	}

	/**
	 * Each mineral this recording: what was counted and from where, and how much the storage went up meanwhile, to
	 * hold against the storage.
	 */
	private static int minerals(FabricClientCommandSource source) {
		Tracker tracker = Tracker.INSTANCE;
		List<Tracker.ItemDiag> diags = tracker.itemDiags();
		Map<String, Long> counted = new LinkedHashMap<>();
		for (Tracker.ItemTally t : tracker.sortedItems()) {
			if (!t.other) {
				counted.merge(t.name, t.count, Long::sum);
			}
		}
		source.sendFeedback(Component.literal("[픽트래커] 광물별 (이번 기록) · 센 것 = 알림 + 채움 + 인벤토리"));
		if (diags.isEmpty() && counted.isEmpty()) {
			source.sendFeedback(Component.literal(" 아직 없어요. 기록 중에 광물을 먹으면 여기 나와요."));
			return 1;
		}
		for (Tracker.ItemDiag d : diags) {
			long total = counted.getOrDefault(d.name, 0L);
			StringBuilder line = new StringBuilder(" " + d.name + ": 센 것 " + HudRenderer.fmt(total) + "개 (알림 "
					+ HudRenderer.fmt(d.fromNotices) + " · 채움 " + HudRenderer.fmt(d.filled) + " · 인벤토리 "
					+ HudRenderer.fmt(d.fromInventory) + ")");
			if (d.storageFrom >= 0) {
				line.append(" · 저장소 ").append(HudRenderer.fmt(d.storageFrom)).append(" → ")
						.append(HudRenderer.fmt(d.storageTo)).append(" (+").append(HudRenderer.fmt(d.storageTo - d.storageFrom))
						.append(d.mixed ? ", 중간에 창·인출 등 있었음)" : ")");
			}
			source.sendFeedback(Component.literal(line.toString()));
			List<String> more = new ArrayList<>();
			if (d.capped > 0) {
				more.add("저장소가 덜 늘어서 뺀 것 " + d.capped + "개");
			}
			if (d.unfilled > 0) {
				more.add("저장소가 더 늘었지만 안 센 것 " + d.unfilled + "개");
			}
			if (d.repeats > 0) {
				more.add("같은 알림 " + d.repeats + "번 (" + d.repeatItems + "개)");
			}
			if (!more.isEmpty()) {
				source.sendFeedback(Component.literal("   └ " + String.join(" · ", more)));
			}
			counted.remove(d.name);
		}
		for (Map.Entry<String, Long> e : counted.entrySet()) {
			source.sendFeedback(Component.literal(" " + e.getKey() + ": 센 것 " + HudRenderer.fmt(e.getValue()) + "개"));
		}
		return 1;
	}

	/** The last decisions (counted or not, and why), oldest first. */
	private static int recent(FabricClientCommandSource source) {
		List<String> notes = Tracker.INSTANCE.recentNotes();
		int from = Math.max(0, notes.size() - RECENT_SHOWN);
		source.sendFeedback(Component.literal("[픽트래커] 최근 판정 " + (notes.size() - from) + "개 (위가 오래된 것)"));
		if (notes.isEmpty()) {
			source.sendFeedback(Component.literal(" 아직 없어요. 광질하면서 저장소 알림이 뜨면 여기 쌓여요."));
		}
		for (String note : notes.subList(from, notes.size())) {
			source.sendFeedback(Component.literal(" " + note));
		}
		return 1;
	}

	/** Lines /광질 진단 최근 shows. */
	private static final int RECENT_SHOWN = 20;

	private static int reply(FabricClientCommandSource source, String text) {
		source.sendFeedback(Component.literal("[픽트래커] " + text));
		return 1;
	}

	private static int help(FabricClientCommandSource source) {
		String[] lines = {
				"[픽트래커] 명령어",
				" /광질 설정 — 설정창 열기 (F9)",
				" /광질 통계 — 날짜별 광질 기록 보기 (F10)",
				" /광질 시작, /광질 종료 — 기록 직접 시작/끝내기 (F7)",
				" /광질 초기화 — 집계판 숫자 초기화 (F8 두 번)",
				" /광질 숨기기, /광질 보이기 — 집계판 숨기기/보이기 (F6)",
				" /광질 알림 켜기, /광질 알림 끄기 — 획득 알림",
				" /광질 블록 켜기, /광질 블록 끄기 — 먹은 광물을 블록으로 보기 (B)",
				" /광질 편집 — 위치·크기 바꾸기",
				" /광질 진단 — 숫자가 이상할 때 확인용",
				" /광질 진단 광물 — 광물마다 센 것과 저장소가 늘어난 개수 비교"
		};
		for (String line : lines) {
			source.sendFeedback(Component.literal(line));
		}
		return 1;
	}
}
