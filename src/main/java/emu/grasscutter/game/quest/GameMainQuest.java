package emu.grasscutter.game.quest;

import java.util.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.excels.QuestData;
import emu.grasscutter.game.quest.enums.LogicType;
import emu.grasscutter.game.quest.enums.QuestTrigger;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.server.packet.send.PacketCodexDataUpdateNotify;
import emu.grasscutter.utils.Position;
import it.unimi.dsi.fastutil.booleans.BooleanArrayList;
import lombok.Getter;
import org.bson.types.ObjectId;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.annotations.Indexed;
import dev.morphia.annotations.Transient;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.binout.MainQuestData.*;
import emu.grasscutter.data.excels.RewardData;
import emu.grasscutter.database.DatabaseHelper;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.quest.enums.ParentQuestState;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.net.proto.ChildQuestOuterClass.ChildQuest;
import emu.grasscutter.net.proto.ParentQuestOuterClass.ParentQuest;
import emu.grasscutter.server.packet.send.PacketFinishedParentQuestUpdateNotify;
import emu.grasscutter.server.packet.send.PacketQuestProgressUpdateNotify;

import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.ScriptException;

import static emu.grasscutter.config.Configuration.SCRIPT;

@Entity(value = "quests", useDiscriminator = false)
public class GameMainQuest {
    @Id private ObjectId id;
    @Indexed @Getter private int ownerUid;
    @Transient @Getter private Player owner;
    @Transient @Getter private QuestManager questManager;
    @Getter private Map<Integer, GameQuest> childQuests;
    @Getter private int parentQuestId;
    @Getter private int[] questVars;
    //QuestUpdateQuestVarReq is sent in two stages...
    @Getter private List<Integer> questVarsUpdate;
    @Getter private ParentQuestState state;
    @Getter private boolean isFinished;
    @Getter List<QuestGroupSuite> questGroupSuites;

    @Getter int[] suggestTrackMainQuestList;
    @Getter private Map<Integer,TalkData> talks;
    //key is subId
    private Map<Integer,Position> rewindPositions;  // These should be replaced with a new datatype holding pos and rot. Database migration will be a pain.
    private Map<Integer,Position> rewindRotations;

    @Deprecated // Morphia only. Do not use.
    public GameMainQuest() {}

    public GameMainQuest(Player player, int parentQuestId) {
        this.owner = player;
        this.ownerUid = player.getUid();
        this.questManager = player.getQuestManager();
        this.parentQuestId = parentQuestId;
        this.childQuests = new HashMap<>();
        this.talks = new HashMap<>();
        //official server always has a list of 5 questVars, with default value 0
        this.questVars = new int[] {0,0,0,0,0};
        this.state = ParentQuestState.PARENT_QUEST_STATE_NONE;
        this.questGroupSuites = new ArrayList<>();
        this.rewindPositions = new HashMap<>();
        this.rewindRotations = new HashMap<>();
        this.addAllChildQuests();
        this.addRewindPoints();
    }

    private void addAllChildQuests() {
        Arrays.stream(GameData.getMainQuestDataMap().get(this.parentQuestId).getSubQuests()).map(SubQuestData::getSubId).forEach(subQuestId ->
            this.childQuests.put(subQuestId, new GameQuest(this, GameData.getQuestDataMap().get(subQuestId))));
    }

    public void setOwner(Player player) {
        if (player.getUid() != this.getOwnerUid()) return;
        this.owner = player;
    }

    public int getQuestVar(int i) {
        return this.questVars[i];
    }
    public void setQuestVar(int i, int value) {
        int previousValue = this.questVars[i];
        this.questVars[i] = value;
        Grasscutter.getLogger().debug("questVar {} value changed from {} to {}", i, previousValue, value);
    }

    public void incQuestVar(int i, int inc) {
        int previousValue = this.questVars[i];
        this.questVars[i] += inc;
        Grasscutter.getLogger().debug("questVar {} value incremented from {} to {}", i, previousValue, previousValue + inc);
    }

    public void decQuestVar(int i, int dec) {
        int previousValue = this.questVars[i];
        this.questVars[i] -= dec;
        Grasscutter.getLogger().debug("questVar {} value decremented from {} to {}", i, previousValue, previousValue - dec);
    }


    public GameQuest getChildQuestById(int id) {
        return this.getChildQuests().get(id);
    }
    public GameQuest getChildQuestByOrder(int order) {
        return this.getChildQuests().values().stream().filter(p -> p.getQuestData().getOrder() == order).toList().get(0);
    }

    public void finish() {
        this.isFinished = true;
        this.state = ParentQuestState.PARENT_QUEST_STATE_FINISHED;

        this.getOwner().getSession().send(new PacketFinishedParentQuestUpdateNotify(this));
        this.getOwner().getSession().send(new PacketCodexDataUpdateNotify(this));

        this.save();

        // Add rewards
        MainQuestData mainQuestData = GameData.getMainQuestDataMap().get(this.getParentQuestId());
        for (int rewardId : mainQuestData.getRewardIdList()) {
            RewardData rewardData = GameData.getRewardDataMap().get(rewardId);

            if (rewardData == null) {
                continue;
            }

            getOwner().getInventory().addItemParamDatas(rewardData.getRewardItemList(), ActionReason.QuestReward);
        }

        // handoff main quest
        if (mainQuestData.getSuggestTrackMainQuestList() != null) {
            Arrays.stream(mainQuestData.getSuggestTrackMainQuestList())
                .forEach(getQuestManager()::startMainQuest);
        }
    }
    //TODO
    public void fail() {}
    public void cancel() {}

    // Rewinds to the last finished/unfinished rewind quest, and returns the avatar rewind position (if it exists)
    public List<Position> rewind() {
        if (this.questManager == null) {
            this.questManager = this.getOwner().getQuestManager();
        }
        var sortedByOrder = this.getChildQuests().values().stream()
            .filter(q -> q.getQuestData().isRewind())
            .sorted((a,b) -> a.getQuestData().getOrder() - b.getQuestData().getOrder())
            .toList();
        for (int i = 0; i < sortedByOrder.size(); i++) {
            if (sortedByOrder.get(i).rewind(sortedByOrder.get(i+1))) break;
        }
        List<GameQuest> rewindQuests = getChildQuests().values().stream()
            .filter(p -> (p.getState() == QuestState.QUEST_STATE_UNFINISHED || p.getState() == QuestState.QUEST_STATE_FINISHED) && p.getQuestData().isRewind()).toList();
        for (GameQuest quest : rewindQuests) {
            if (rewindPositions.containsKey(quest.getSubQuestId())) {
                List<Position> posAndRot = new ArrayList<>();
                posAndRot.add(0, rewindPositions.get(quest.getSubQuestId()));
                posAndRot.add(1, rewindRotations.get(quest.getSubQuestId()));
                return posAndRot;
            }
        }
        return null;
    }
    public void addRewindPoints() {
        Bindings bindings = ScriptLoader.getEngine().createBindings();
        String script = "Quest/Share/Q" + getParentQuestId() + "ShareConfig.lua";
        CompiledScript cs = ScriptLoader.getScript(script);

        //mainQuest 303 doesn't have a ShareConfig
        if (cs == null) {
            Grasscutter.getLogger().debug("Couldn't find " + script);
            return;
        }


        // Eval script
        try {
            cs.eval(bindings);
            var fullGlobals = GameData.getScriptSceneDataMap().get("flat.luas.scenes.full_globals.lua.json");
            if (fullGlobals == null) return;

            var rewindDataMap = ScriptLoader.getSerializer().toMap(RewindData.class, bindings.get("rewind_data"));
            rewindDataMap.forEach((subId, questRewind) -> {
                int id = Integer.valueOf(subId);
                try {  // Wrapping all of this in a try-catch saves **a lot** of null-checking
                    var sceneId = GameData.getQuestDataMap().get(id).getGuide().getGuideScene();
                    var avatarPos = questRewind.getAvatar().getPos();
                    var dummyPoints = fullGlobals.getScriptObjectList().get(sceneId + "/scene" + sceneId + "_dummy_points.lua").getDummyPoints();
                    List<Float> avatarPosPos = dummyPoints.get(avatarPos + ".pos");
                    List<Float> avatarPosRot = dummyPoints.get(avatarPos + ".rot");
                    rewindPositions.put(id, new Position(avatarPosPos.get(0), avatarPosPos.get(1), avatarPosPos.get(2)));
                    rewindRotations.put(id, new Position(avatarPosRot.get(0), avatarPosRot.get(1), avatarPosRot.get(2)));
                    Grasscutter.getLogger().debug("Successfully loaded rewind position for subQuest " + subId);
                } catch (NullPointerException e) {
                    Grasscutter.getLogger().error("An error occurred while loading rewind positions for subQuest " + subId);
                }
            });

        } catch (ScriptException e) {
            Grasscutter.getLogger().error("An error occurred while loading rewind positions");
        }
    }

    public void tryAcceptSubQuests(QuestTrigger condType, String paramStr, int... params) {
        try {
            this.getChildQuests().values().stream()
                .filter(p -> p.getState() == QuestState.QUEST_STATE_UNSTARTED)
                .filter(p -> p.getQuestData().getAcceptCond().stream().anyMatch(q -> q.getType() == condType))
                .forEach(subQuestWithCond -> {
                        var questData = subQuestWithCond.getQuestData();
                        var acceptCond = questData.getAcceptCond();
                        var accept = new BooleanArrayList(acceptCond.size());
                        acceptCond.forEach(cond -> accept.add(this.getOwner().getServer().getQuestSystem().triggerCondition(subQuestWithCond, cond, paramStr, params)));

                        if (LogicType.calculate(questData.getAcceptCondComb(), accept)) {
                            subQuestWithCond.start();
                            this.getQuestManager().getAddToQuestListUpdateNotify().add(subQuestWithCond);
                        }
                    });
            this.save();
        } catch (Exception e) {
            Grasscutter.getLogger().error("An error occurred while trying to accept quest.", e);
        }
    }

    public void tryFailSubQuests(QuestTrigger condType, String paramStr, int... params) {
        try {
            List<GameQuest> subQuestsWithCond = getChildQuests().values().stream()
                .filter(p -> p.getState() == QuestState.QUEST_STATE_UNFINISHED)
                .filter(p -> p.getQuestData().getFailCond().stream().anyMatch(q -> q.getType() == condType))
                .toList();

            for (GameQuest subQuestWithCond : subQuestsWithCond) {
                List<QuestData.QuestCondition> failCond = subQuestWithCond.getQuestData().getFailCond();

                for (int i = 0; i < subQuestWithCond.getQuestData().getFailCond().size(); i++) {
                    QuestData.QuestCondition condition = failCond.get(i);
                    if (condition.getType() == condType) {
                        boolean result = this.getOwner().getServer().getQuestSystem().triggerContent(subQuestWithCond, condition, paramStr, params);
                        subQuestWithCond.getFailProgressList()[i] = result ? 1 : 0;
                        if (result) {
                            getOwner().getSession().send(new PacketQuestProgressUpdateNotify(subQuestWithCond));
                        }
                    }
                }

                boolean shouldFail = LogicType.calculate(subQuestWithCond.getQuestData().getFailCondComb(), subQuestWithCond.getFailProgressList());

                if (shouldFail) {
                    subQuestWithCond.fail();
                    getQuestManager().getAddToQuestListUpdateNotify().add(subQuestWithCond);
                }
            }

        } catch (Exception e) {
            Grasscutter.getLogger().error("An error occurred while trying to fail quest.", e);
        }
    }

    public void tryFinishSubQuests(QuestTrigger condType, String paramStr, int... params) {
        try {
            List<GameQuest> subQuestsWithCond = getChildQuests().values().stream()
                //There are subQuests with no acceptCond, but can be finished (example: 35104)
                .filter(p -> p.getState() == QuestState.QUEST_STATE_UNFINISHED && p.getQuestData().getAcceptCond() != null)
                .filter(p -> p.getQuestData().getFinishCond().stream().anyMatch(q -> q.getType() == condType))
                .toList();

            for (GameQuest subQuestWithCond : subQuestsWithCond) {
                List<QuestData.QuestCondition> finishCond = subQuestWithCond.getQuestData().getFinishCond();

                for (int i = 0; i < finishCond.size(); i++) {
                    QuestData.QuestCondition condition = finishCond.get(i);
                    if (condition.getType() == condType) {
                        boolean result = this.getOwner().getServer().getQuestSystem().triggerContent(subQuestWithCond, condition, paramStr, params);
                        subQuestWithCond.getFinishProgressList()[i] = result ? 1 : 0;
                        if (result) {
                            getOwner().getSession().send(new PacketQuestProgressUpdateNotify(subQuestWithCond));
                        }
                    }
                }

                boolean shouldFinish = LogicType.calculate(subQuestWithCond.getQuestData().getFinishCondComb(), subQuestWithCond.getFinishProgressList());

                if (shouldFinish) {
                    subQuestWithCond.finish();
                    getQuestManager().getAddToQuestListUpdateNotify().add(subQuestWithCond);
                }
            }
        } catch (Exception e) {
            Grasscutter.getLogger().debug("An error occurred while trying to finish quest.", e);
        }
    }

    public void save() {
        DatabaseHelper.saveQuest(this);
    }

    public void delete() {
        DatabaseHelper.deleteQuest(this);
    }

    public ParentQuest toProto() {
        ParentQuest.Builder proto = ParentQuest.newBuilder()
                .setParentQuestId(getParentQuestId())
                .setIsFinished(isFinished());

            proto.setParentQuestState(getState().getValue())
                .setCutsceneEncryptionKey(QuestManager.getQuestKey(parentQuestId));
            for (GameQuest quest : this.getChildQuests().values()) {
                if (quest.getState() != QuestState.QUEST_STATE_UNSTARTED) {
                    ChildQuest childQuest = ChildQuest.newBuilder()
                        .setQuestId(quest.getSubQuestId())
                        .setState(quest.getState().getValue())
                        .build();

                    proto.addChildQuestList(childQuest);
                }
            }

        for (int i : getQuestVars()) {
            proto.addQuestVar(i);
        }


        return proto.build();
    }

}
