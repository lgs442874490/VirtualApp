package main

import (
	"database/sql"
	"path/filepath"
	"sync"
	"testing"
)

// 并发激活回归测试：验证 bindDeviceAtomic 修复的 P0 竞态
// （原实现"读-判-写"无互斥，两台设备并发激活同一码可双双绑定成功）。

// newRaceEnv 构造仅含数据库层的测试环境（不需要完整 Server，直测 db 层原子性）。
func newRaceEnv(t *testing.T) (*sql.DB, func()) {
	t.Helper()
	db, err := openDB(filepath.Join(t.TempDir(), "race.db"))
	if err != nil {
		t.Fatalf("openDB: %v", err)
	}
	return db, func() { db.Close() }
}

// TestBindConcurrentTwoDevices 8 个 goroutine 以两台不同设备并发激活同一个码：
// 断言 (1) 恰好一次 bindFirst；(2) 其余全部是 bindOther（不同设备被拒）；
// (3) 库里最终只有一条 active 绑定。
func TestBindConcurrentTwoDevices(t *testing.T) {
	db, cleanup := newRaceEnv(t)
	defer cleanup()

	const code = "ABCD-EFGH-JKLM-NPQR"
	codeID, err := insertCode(db, code, TypeMonth, 30, "", "race-test", nowMs())
	if err != nil {
		t.Fatalf("insertCode: %v", err)
	}

	const N = 8
	var wg sync.WaitGroup
	var mu sync.Mutex
	type result struct {
		device string
		state  bindState
	}
	results := make([]result, 0, N)

	now := nowMs()
	for i := 0; i < N; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			// 偶数 goroutine 用设备 A，奇数用设备 B —— 同机并发 + 双机竞争混合压测
			hash := "devA"
			if i%2 == 1 {
				hash = "devB"
			}
			st, err := bindDeviceAtomic(db, codeID, hash, "label-"+hash, now, now+30*MsPerDay, true)
			if err != nil {
				t.Errorf("goroutine %d: %v", i, err)
				return
			}
			mu.Lock()
			results = append(results, result{device: hash, state: st})
			mu.Unlock()
		}(i)
	}
	wg.Wait()

	// 不变量：恰好一次首绑；所有成功结果（first+same）必须属于同一台设备
	// （= 库里最终那条 active 绑定的设备）；他机结果（other）属于另一台。
	// 这正是"一码一机"在并发下的定义：不可能两台设备同时成功。
	firstCount, sameCount, otherCount := 0, 0, 0
	winnerDev := ""
	for _, r := range results {
		switch r.state {
		case bindFirst:
			firstCount++
			if winnerDev == "" {
				winnerDev = r.device
			} else if winnerDev != r.device {
				t.Fatal("两台设备都拿到首绑权：并发击穿！")
			}
		case bindSame:
			sameCount++
			if r.device != winnerDev {
				t.Fatalf("设备 %s 幂等成功，但胜者是 %s：两台设备同时成功！", r.device, winnerDev)
			}
		case bindOther:
			otherCount++
		}
	}
	if firstCount != 1 {
		t.Fatalf("首绑应恰好发生 1 次，实际 %d 次（并发击穿！）", firstCount)
	}
	if firstCount+sameCount+otherCount != N {
		t.Fatalf("结果数量不符：%d+%d+%d != %d", firstCount, sameCount, otherCount, N)
	}

	active, err := getActiveBinding(db, codeID)
	if err != nil || active == nil {
		t.Fatalf("最终应存在一条 active 绑定: %v", err)
	}
	if active.DeviceHash != winnerDev {
		t.Fatalf("库中胜者 %s 与裁决胜者 %s 不一致", active.DeviceHash, winnerDev)
	}
	t.Logf("并发 %d 次（devA×4 + devB×4）：首绑1、同机幂等%d、他机拒绝%d，胜者=%s",
		N, sameCount, otherCount, winnerDev)
}

// TestBindSameDeviceConcurrent 同一台设备并发激活：应全部幂等成功（1 首绑 + N-1 同机）。
func TestBindSameDeviceConcurrent(t *testing.T) {
	db, cleanup := newRaceEnv(t)
	defer cleanup()

	codeID, err := insertCode(db, "WXYZ-WXYZ-WXYZ-WXYZ", TypePermanent, 0, "", "race-test", nowMs())
	if err != nil {
		t.Fatalf("insertCode: %v", err)
	}

	const N = 8
	var wg sync.WaitGroup
	states := make([]bindState, N)
	now := nowMs()
	for i := 0; i < N; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			st, err := bindDeviceAtomic(db, codeID, "sameDev", "", now, 0, false)
			if err != nil {
				t.Errorf("goroutine %d: %v", i, err)
				return
			}
			states[i] = st
		}(i)
	}
	wg.Wait()

	firstCount, sameCount := 0, 0
	for _, st := range states {
		switch st {
		case bindFirst:
			firstCount++
		case bindSame:
			sameCount++
		case bindOther:
			t.Fatal("同机并发不应被判定为他机")
		}
	}
	if firstCount != 1 || sameCount != N-1 {
		t.Fatalf("同机并发应 1 次首绑 + %d 次幂等，实际 first=%d same=%d", N-1, firstCount, sameCount)
	}
}
