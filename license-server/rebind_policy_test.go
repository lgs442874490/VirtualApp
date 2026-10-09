package main

import (
	"path/filepath"
	"testing"
)

// 换绑策略回归：用户拍板"一码一次激活、禁止换绑"。
// 默认（RebindMax=0）换绑必须被拒；显式放开（>0）仍走限次逻辑。

func TestRebindForbiddenByDefault(t *testing.T) {
	db, err := openDB(filepath.Join(t.TempDir(), "rebind.db"))
	if err != nil {
		t.Fatalf("openDB: %v", err)
	}
	defer db.Close()

	// 造码（用 genCode 生成带合法校验位的码）并用设备 A 首绑
	code, err := genCode()
	if err != nil {
		t.Fatalf("genCode: %v", err)
	}
	codeID, err := insertCode(db, code, TypeMonth, 30, "", "t", nowMs())
	if err != nil {
		t.Fatalf("insertCode: %v", err)
	}
	now := nowMs()
	if _, err := bindDeviceAtomic(db, codeID, "devA", "", now, now+30*MsPerDay, true); err != nil {
		t.Fatalf("首绑: %v", err)
	}

	// 默认策略（0 = 禁止）：管理员即便持正确令牌，换绑也必须被拒
	resCode, _, _, err := performRebind(db, 0, code, "devB", "test", now, "", "")
	if err != nil {
		t.Fatalf("performRebind: %v", err)
	}
	if resCode != CodeRebindLimit {
		t.Fatalf("默认策略下换绑应返回 %d(RebindLimit)，实际 %d", CodeRebindLimit, resCode)
	}

	// 绑定未被破坏：仍是设备 A
	active, err := getActiveBinding(db, codeID)
	if err != nil || active == nil || active.DeviceHash != "devA" {
		t.Fatalf("被拒的换绑不应影响原绑定: %+v %v", active, err)
	}

	// 显式放开（>0）时换绑仍可用（客服救急路径）
	resCode, _, _, err = performRebind(db, 1, code, "devB", "manual", now, "", "")
	if err != nil {
		t.Fatalf("performRebind(放开): %v", err)
	}
	if resCode != CodeOK {
		t.Fatalf("放开后换绑应成功，实际 code=%d", resCode)
	}
	active, err = getActiveBinding(db, codeID)
	if err != nil || active == nil || active.DeviceHash != "devB" {
		t.Fatalf("放开后应绑到 devB: %+v %v", active, err)
	}
}
