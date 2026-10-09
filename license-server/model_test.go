package main

import (
	"encoding/json"
	"strings"
	"testing"
)

// TestVerifyDataClosedStatusSet 回归「冻结契约 §5.1：data.status 为封闭四值集合」。
//
// 未激活分支（code=1007）不返回 status：即 VerifyData.Status 为零值时，
// 序列化结果**不得出现 "status" 键**（依赖 `json:"status,omitempty"`）。
// 若有人把 omitempty 去掉、或为未激活补第 5 个 status 值，本测试会立刻失败。
func TestVerifyDataClosedStatusSet(t *testing.T) {
	// 1) 未激活路径：status 留零值 → 必须省略该字段
	notActivated, err := json.Marshal(VerifyData{Type: TypeMonth})
	if err != nil {
		t.Fatalf("序列化 VerifyData 失败: %v", err)
	}
	if strings.Contains(string(notActivated), `"status"`) {
		t.Fatalf("未激活响应不应包含 status 字段, got=%s", notActivated)
	}

	// 2) 四个合法状态：必须原样出现
	for _, st := range []string{
		VerifyStatusActive,
		VerifyStatusExpired,
		VerifyStatusRevoked,
		VerifyStatusDeviceMismatch,
	} {
		b, err := json.Marshal(VerifyData{Status: st})
		if err != nil {
			t.Fatalf("序列化 VerifyData(status=%s) 失败: %v", st, err)
		}
		if !strings.Contains(string(b), `"status":"`+st+`"`) {
			t.Fatalf("期望包含 status=%s, got=%s", st, b)
		}
	}
}
