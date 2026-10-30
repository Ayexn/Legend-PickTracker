package com.miningtracker;

/** How the tally board is laid out (설정 → 집계판 모양). */
public enum HudLayout {
	/** Mined blocks by type, minerals by name. */
	DETAIL("detail", "자세히", "자세히: 캔 블록은 종류별로, 광물은 개수와 막대로"),
	/** Mined blocks as one total, minerals by name. */
	SLIM("slim", "슬림", "슬림: 캔 블록은 합계만, 광물은 개수와 막대로"),
	/** Minerals as inventory-style slots. */
	ICONS("icons", "아이콘", "아이콘: 광물을 색깔 칸에 아이콘과 개수로"),
	/** One line at the top of the screen. */
	BAR("bar", "한 줄", "한 줄: 화면 위 가운데에 한 줄로 작게");

	public final String id;
	public final String label;
	public final String description;

	HudLayout(String id, String label, String description) {
		this.id = id;
		this.label = label;
		this.description = description;
	}

	public static HudLayout byId(String id) {
		for (HudLayout l : values()) {
			if (l.id.equals(id)) {
				return l;
			}
		}
		return SLIM;
	}
}
