package com.miningtracker;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Client-side commands (never sent to the server): /광질 ... */
public final class Commands {
	private Commands() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
		dispatcher.register(literal("광질")
				.executes(c -> help(c.getSource()))
				.then(literal("도움말").executes(c -> help(c.getSource())))
				.then(literal("초기화").executes(c -> {
					Tracker.INSTANCE.reset();
					return reply(c.getSource(), "기록을 초기화했어요.");
				}))
				.then(literal("일시정지").executes(c -> {
					Tracker.INSTANCE.setRunning(false);
					return reply(c.getSource(), "측정을 일시정지했어요. 다시 시작: /광질 시작");
				}))
				.then(literal("시작").executes(c -> {
					Tracker.INSTANCE.setRunning(true);
					return reply(c.getSource(), "측정을 시작했어요.");
				}))
				.then(literal("숨기기").executes(c -> {
					ModConfig.get().hudVisible = false;
					ModConfig.save();
					return reply(c.getSource(), "집계판을 숨겼어요. 다시 보기: /광질 보이기");
				}))
				.then(literal("보이기").executes(c -> {
					ModConfig.get().hudVisible = true;
					ModConfig.save();
					return reply(c.getSource(), "집계판을 다시 보여줄게요.");
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
				.then(literal("진단").executes(c -> diagnose(c.getSource()))));
	}

	/** Shows which detection paths fired, to help fix wrong counts on a particular server. */
	private static int diagnose(FabricClientCommandSource source) {
		Tracker.Stats s = Tracker.INSTANCE.stats;
		String[] lines = {
				"[광질 측정] 진단 (초기화 이후)",
				" 줍기 신호로 센 획득: " + s.packetPickups + "번",
				" 옆에서 사라진 걸로 센 획득: " + s.fallbackPickups + "번",
				" 저장소로 간 개수: " + s.toStorage + ", 인벤으로 온 개수: " + s.toInventory,
				" 주변 블록으로 센 블록(3x3 등): " + s.neighborBlocks + "개",
				" 서버가 되돌려서 뺀 블록: " + s.restoredBlocks + "개",
				" 합쳐짐/청소/방치로 제외: " + s.mergeSkips + " / " + s.cleanupSkips + " / " + s.longReachSkips
		};
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
				" /광질 초기화 — 기록 지우기",
				" /광질 일시정지, /광질 시작 — 측정 멈추기/다시 시작",
				" /광질 숨기기, /광질 보이기 — 집계판 숨기기/보이기",
				" /광질 알림 켜기, /광질 알림 끄기 — 획득 알림",
				" /광질 편집 — 위치·크기 바꾸기",
				" /광질 진단 — 숫자가 이상할 때 확인용",
				" 단축키: F6 집계판, F7 일시정지, F8 두 번 초기화, F9 편집"
		};
		for (String line : lines) {
			source.sendFeedback(Component.literal(line));
		}
		return 1;
	}
}
