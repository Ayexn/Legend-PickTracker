package com.miningtracker;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

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
				.then(literal("끝").executes(c -> stop(c.getSource())))
				// 0.1.1 had "일시정지": keep it working, it now ends the recording
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
				.then(literal("편집").executes(c -> {
					Tracker.INSTANCE.requestEditor();
					return 1;
				}))
				.then(literal("통계").executes(c -> {
					Tracker.INSTANCE.requestStats();
					return 1;
				}))
				.then(literal("진단").executes(c -> diagnose(c.getSource()))));
	}

	private static int start(FabricClientCommandSource source) {
		Tracker tracker = Tracker.INSTANCE;
		if (tracker.isRecording()) {
			return reply(source, "이미 기록 중이에요. 끝내기: /광질 끝");
		}
		tracker.startRecording(true);
		ModConfig cfg = ModConfig.get();
		return reply(source, cfg.autoMode
				? "기록을 시작했어요. " + Tracker.formatSeconds(cfg.endAfterSeconds) + " 동안 안 캐면 끝나요."
				: "기록을 시작했어요. 끝내기: /광질 끝 (또는 F7)");
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
		lines.add("[광질 측정] 진단");
		String state = !tracker.isRecording() ? "기록 안 함"
				: tracker.isManualSession() ? "기록 중 (직접 시작)" : "기록 중 (자동으로 시작)";
		lines.add(" 상태: " + state + " · 방식: " + (cfg.autoMode ? "자동" : "수동")
				+ " · 끝 판정: " + Tracker.formatSeconds(cfg.endAfterSeconds));
		ClientLevel level = source.getClient().level;
		if (level != null) {
			lines.add(" 월드: " + Tracker.dimensionId(level) + (Tracker.isWildWorld(level) ? " (야생으로 봐요)" : " (야생 아님)"));
			lines.add(" 이곳 자동 기록: " + (Tracker.isAutoPlace(level) ? "켜짐" : "꺼짐") + " · 위치 키: " + Tracker.placeKey(level));
		}
		Tracker.Stats s = tracker.stats;
		lines.add(" 줍기 신호로 센 획득: " + s.packetPickups + "번"
				+ (s.latePacketPickups > 0 ? " (사라진 뒤에 온 신호 " + s.latePacketPickups + "번)" : ""));
		lines.add(" 옆에서 사라진 걸로 센 획득: " + s.fallbackPickups + "번");
		lines.add(" 자석 범위(5칸)에서 사라진 걸로 센 획득: " + s.magnetPickups + "번"
				+ (s.magnetUnconfirmed > 0 ? " (자석이 안 보여서 안 센 것 " + s.magnetUnconfirmed + "번)" : ""));
		lines.add(" 안 센 것: 합쳐짐 " + s.mergeSkips + ", 청소 " + s.cleanupSkips + ", 오래 놓여 있음 " + s.longReachSkips
				+ ", 조금 떨어져서 사라짐 " + s.notNearSkips + ", 다른 사람 " + s.otherPlayerSkips + ", 용암·불 " + s.lavaSkips);
		long[] md = s.mergeDistance;
		lines.add(" 합쳐질 때 거리: 1칸 안 " + md[0] + "번, 1~2칸 " + md[1] + "번, 2~3칸 " + md[2] + "번, 3칸 넘게 " + md[3] + "번");
		lines.add(" 주변 블록으로 센 블록(3x3 등): " + s.neighborBlocks + "개");
		lines.add(" 서버가 되돌린 블록: " + s.restoredBlocks + "개 (그중 서버가 바로 다시 깨서 다시 센 것 " + s.rebrokenBlocks + "개)");
		for (String line : lines) {
			source.sendFeedback(Component.literal(line));
		}
		return 1;
	}

	private static int reply(FabricClientCommandSource source, String text) {
		source.sendFeedback(Component.literal("[광질 측정] " + text));
		return 1;
	}

	private static int help(FabricClientCommandSource source) {
		String[] lines = {
				"[광질 측정] 명령어",
				" /광질 설정 — 설정창 열기 (F9)",
				" /광질 통계 — 날짜별 광질 기록 보기 (F10)",
				" /광질 시작, /광질 끝 — 기록 직접 시작/끝내기 (F7)",
				" /광질 초기화 — 집계판 숫자 초기화 (F8 두 번)",
				" /광질 숨기기, /광질 보이기 — 집계판 숨기기/보이기 (F6)",
				" /광질 알림 켜기, /광질 알림 끄기 — 획득 알림",
				" /광질 편집 — 위치·크기 바꾸기",
				" /광질 진단 — 숫자가 이상할 때 확인용"
		};
		for (String line : lines) {
			source.sendFeedback(Component.literal(line));
		}
		return 1;
	}
}
