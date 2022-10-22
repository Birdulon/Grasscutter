package emu.grasscutter.game.quest.enums;

import java.util.Arrays;
import java.util.stream.IntStream;

import it.unimi.dsi.fastutil.booleans.BooleanList;

public enum LogicType {
	LOGIC_NONE (0),
	LOGIC_AND (1),
	LOGIC_OR (2),
	LOGIC_NOT (3),
	LOGIC_A_AND_ETCOR (4),
	LOGIC_A_AND_B_AND_ETCOR (5),
	LOGIC_A_OR_ETCAND (6),
	LOGIC_A_OR_B_OR_ETCAND (7),
	LOGIC_A_AND_B_OR_ETCAND (8);
	
	private final int value;
	
	LogicType(int id) {
		this.value = id;
	}

	public int getValue() {
		return value;
	}

    public static boolean calculate(LogicType logicType, int[] progress) {
        if (logicType == null) {
            return progress[0] == 1;
        }

        return switch (logicType) {
            case LOGIC_NONE -> progress[0] == 1;
            case LOGIC_AND -> Arrays.stream(progress).allMatch(i -> i == 1);
            case LOGIC_OR -> Arrays.stream(progress).anyMatch(i -> i == 1);
            default -> Arrays.stream(progress).anyMatch(i -> i == 1);
        };
    }

    public static boolean calculate(LogicType logicType, boolean... progress) {
        return calculate(logicType, BooleanList.of(progress));
    }

    public static boolean calculate(LogicType logicType, BooleanList progress) {
        if (progress.size() < 1) return false;
        if (logicType == null) return progress.getBoolean(0);

        return switch (logicType) {
            case LOGIC_NONE -> progress.getBoolean(0);
            case LOGIC_AND -> progress.stream().allMatch(b -> b);
            case LOGIC_OR -> progress.stream().anyMatch(b -> b);
            default -> progress.stream().anyMatch(b -> b);
        };
    }
}
