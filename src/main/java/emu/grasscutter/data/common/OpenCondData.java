package emu.grasscutter.data.common;

import it.unimi.dsi.fastutil.ints.IntList;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class OpenCondData {
    private String condType;
    private IntList paramList;
}
