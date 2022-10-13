package emu.grasscutter.data.excels;

import emu.grasscutter.data.GameResource;
import emu.grasscutter.data.ResourceType;
import it.unimi.dsi.fastutil.ints.IntList;

import java.util.List;

@ResourceType(name = "TowerScheduleExcelConfigData.json")
public class TowerScheduleData extends GameResource {
    private int scheduleId;
    private IntList entranceFloorId;
    private List<ScheduleDetail> schedules;
    private int monthlyLevelConfigId;
    
    @Override
    public int getId() {
        return scheduleId;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        this.schedules = this.schedules.stream()
                .filter(item -> item.getFloorList().size() > 0)
                .toList();
    }

    public int getScheduleId() {
        return scheduleId;
    }

    public IntList getEntranceFloorId() {
        return entranceFloorId;
    }

    public List<ScheduleDetail> getSchedules() {
        return schedules;
    }
    
    public int getMonthlyLevelConfigId() {
        return monthlyLevelConfigId;
    }

    public static class ScheduleDetail{
        private IntList floorList;

        public IntList getFloorList() {
            return floorList;
        }
    }
}
