package com.carlos.vaultserver.migrant;

import com.carlos.vaultserver.VaultStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 移民单测共用构造器（不匹配 *Test，JUnit 不会误跑）。 */
final class MigrantTestSupport {

    static final long ACTIVITY_ID = 2423001L;
    static final long TARGET_KID = 1001L;
    static final long CURRENT_KID = 888L;
    static final long ROLE_UID = 1001L;
    static final long STANDARD_POINTS = 1000L;
    static final long PERSONAL_POINTS = 500L;

    private MigrantTestSupport() {
    }

    /** 标准王国详情（非强盛，名额 55，战力档位 1）。 */
    static MigrantState.KingdomInfo kingdom(long kid, long migrantNum, int powerGear) {
        return new MigrantState.KingdomInfo(kid, 0, migrantNum, migrantNum, 0, powerGear,
                1, 1, "K" + kid, 0L, 0L, 0, 0);
    }

    /** 默认“全部通过”的快照构造器。 */
    static SnapshotBuilder builder(long activityStartSeconds, long serverNowMillis) {
        return new SnapshotBuilder(activityStartSeconds, serverNowMillis);
    }

    /** 活动已进入自由移民阶段的标准快照（open 前 1 小时创建任务的典型状态）。 */
    static SnapshotBuilder readyBuilder() {
        long startSec = 1_700_000_000L;
        long nowSec = startSec + MigrantRules.FREEDOM_START_OFFSET_SECONDS + 3600L;
        return new SnapshotBuilder(startSec, nowSec * 1000L);
    }

    static final class SnapshotBuilder {
        boolean baseInitialized = true;
        boolean migrantInitialized = true;
        boolean backpackInitialized = true;
        boolean allianceInitialized = true;
        boolean serverTimeInitialized = true;
        long uid = ROLE_UID;
        long currentKid = CURRENT_KID;
        long registerTimeSeconds;
        int stoveLevel = 30;
        int office;
        long allianceId;
        boolean supremacyWarActive;
        boolean migrantFirstLoginNotified;
        long serverNowMillis;
        boolean roleClockReady = true;
        int utcOffsetSeconds = 8;
        long activityId = ACTIVITY_ID;
        long activityStartSeconds;
        long activityEndSeconds;
        long activitySerialNo = 7L;
        long competitionAreaId = 1L;
        long standardPoints = STANDARD_POINTS;
        long personalPoints = PERSONAL_POINTS;
        long lastMigrantSeconds;
        long presidentUid;
        long permitCount = 20L;
        final Map<Long, Long> kidOpenTimes = new LinkedHashMap<>();
        final Map<Long, MigrantState.KingdomInfo> kingdoms = new LinkedHashMap<>();
        final Map<Long, Integer> roleCountByKingdom = new LinkedHashMap<>();
        final List<Long> invitationKids = new ArrayList<>();

        SnapshotBuilder(long activityStartSeconds, long serverNowMillis) {
            this.activityStartSeconds = activityStartSeconds;
            this.activityEndSeconds = activityStartSeconds + MigrantRules.ACTIVITY_DURATION_SECONDS;
            this.serverNowMillis = serverNowMillis;
            kidOpenTimes.put(CURRENT_KID, activityStartSeconds);
            kidOpenTimes.put(TARGET_KID, activityStartSeconds);
            kingdoms.put(CURRENT_KID, kingdom(CURRENT_KID, 10L, 1));
            kingdoms.put(TARGET_KID, kingdom(TARGET_KID, 0L, 1));
            roleCountByKingdom.put(TARGET_KID, 0);
        }

        SnapshotBuilder alliance(long allianceId) {
            this.allianceId = allianceId;
            return this;
        }

        SnapshotBuilder personalPoints(long points) {
            this.personalPoints = points;
            return this;
        }

        SnapshotBuilder permits(long count) {
            this.permitCount = count;
            return this;
        }

        SnapshotBuilder lastMigrantSeconds(long value) {
            this.lastMigrantSeconds = value;
            return this;
        }

        SnapshotBuilder targetKingdom(MigrantState.KingdomInfo info) {
            this.kingdoms.put(info.kid, info);
            return this;
        }

        SnapshotBuilder targetRoleCount(int count) {
            this.roleCountByKingdom.put(TARGET_KID, count);
            return this;
        }

        SnapshotBuilder invited(long kid) {
            this.invitationKids.add(kid);
            return this;
        }

        SnapshotBuilder activitySupported(boolean supported) {
            if (!supported) activityId = 1L;
            return this;
        }

        SnapshotBuilder serverNowMillis(long millis) {
            this.serverNowMillis = millis;
            return this;
        }

        MigrantState.Snapshot build() {
            return new MigrantState.Snapshot(baseInitialized, migrantInitialized,
                    backpackInitialized, allianceInitialized, serverTimeInitialized, uid, currentKid,
                    registerTimeSeconds, stoveLevel, office, allianceId, supremacyWarActive,
                    migrantFirstLoginNotified, serverNowMillis, roleClockReady, utcOffsetSeconds,
                    activityId, activityStartSeconds, activityEndSeconds, activitySerialNo,
                    competitionAreaId, standardPoints, personalPoints, lastMigrantSeconds,
                    presidentUid, permitCount, kidOpenTimes, kingdoms, roleCountByKingdom,
                    invitationKids);
        }
    }

    /** 造一个带单角色的账号池（供 MigrantApi 单测）。 */
    static VaultStore storeWithRole(java.nio.file.Path dataDir, String fpid, String sid,
                                    String uid, String name) {
        VaultStore store = new VaultStore(dataDir);
        store.load();
        VaultStore.Account account = new VaultStore.Account(fpid);
        account.label = "账号" + fpid;
        account.token = "test-token-" + fpid;
        VaultStore.RoleEntry role = new VaultStore.RoleEntry(sid, uid);
        role.name = name;
        account.roles.put(VaultStore.roleKey(sid, uid), role);
        store.replaceAll(java.util.Collections.singletonList(account));
        return store;
    }

    /** 造一个最小任务（默认 WAITING）。 */
    static MigrantJob job(String jobId, String fpid, long sid, long uid, long freedomStartMillis) {
        MigrantJob job = new MigrantJob();
        job.jobId = jobId;
        job.fpid = fpid;
        job.sid = sid;
        job.uid = uid;
        job.accountRoleId = uid;
        job.accountName = "角色" + uid;
        job.originalKid = (int) CURRENT_KID;
        job.targetKid = (int) TARGET_KID;
        job.targetName = "K1001";
        job.activityId = ACTIVITY_ID;
        job.activitySerialNo = 7L;
        job.status = MigrantJob.WAITING;
        job.freedomStartMillis = freedomStartMillis;
        job.freedomEndMillis = freedomStartMillis + 48L * 3600_000L;
        job.preparationStartMillis = freedomStartMillis - MigrantRules.PREPARE_WINDOW_MILLIS;
        job.createdAtMillis = System.currentTimeMillis();
        job.updatedAtMillis = job.createdAtMillis;
        job.recallBeforeOpen = true;
        job.recallReady = false;
        job.precheck = new org.json.JSONObject().put("ready", true);
        return job;
    }
}
