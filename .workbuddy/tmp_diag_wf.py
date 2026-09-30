# -*- coding: utf-8 -*-
"""生成 vFlow 定位/逆地理 诊断工作流 JSON（只读，不改任何数据）"""
import json, os

DST = r'E:/xwechat_files/qqnbzrm_856a/msg/file/2026-09/诊断-定位与逆地理.json'

# 单行 POSIX sh：逐段输出中间结果
DIAG_CMD = (
    r'''echo "【1】执行身份 = $(whoami 2>/dev/null || echo 未知)"; '''
    r'''echo "【2】dumpsys 匹配行数 = $(dumpsys location 2>&1 | grep -c 'last location=Location')"; '''
    r'''echo "【3】匹配到的原始行 = $(dumpsys location 2>&1 | grep -m1 'last location=Location' | cut -c1-200)"; '''
    r'''LL=$(dumpsys location 2>&1 | grep -m1 'last location=Location' '''
    r'''| sed -E 's/.*Location\[[a-zA-Z]+ ([0-9.-]+),([0-9.-]+).*/\1,\2/'); '''
    r'''echo "【4】坐标抽取结果 = $LL"; '''
    r'''echo "【5】curl 路径 = $(command -v curl 2>/dev/null || echo 未安装)"; '''
    r'''R=$(curl -s --max-time 12 'https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=31.8&longitude=119.9&localityLanguage=zh-Hans' 2>&1 | head -c 400); '''
    r'''echo "【6】逆地理接口响应(固定坐标31.8,119.9) = $R"; '''
    r'''N=$(printf '%s' "$R" | grep -oE '"(city|locality)": *"[^"]*"' | sed -E 's/.*"([^"]*)"$/\1/'); '''
    r'''echo "【7】city/locality 抽取 = $(printf '%s' "$N" | tr '\n' ' / ')"; '''
    r'''echo "【8】wttr.in 连通性 = $(curl -s -o /dev/null -w 'HTTP %{http_code}' --max-time 12 'https://wttr.in/31.8,119.9?format=%25C' 2>&1)"'''
)

NOTE = (
    "诊断用，只读，不改任何数据。第 1 步把定位与网络链路的每一段中间结果打成多行文本，"
    "第 2 步用弹窗显示。看哪一段开始变空或者报错，就知道断在哪里：\n"
    "【2】=0 说明 dumpsys 里没有 last location 行（定位服务被关 / 刚开机没定位过）；\n"
    "【4】拿不到 纬度,经度 说明第 2 步的 sed 没匹配上；\n"
    "【5】=未安装 说明 shell 环境里没有 curl；\n"
    "【6】为空或报错 说明逆地理接口在这台设备上不可达（该接口是境外服务）；\n"
    "【7】为空但【6】正常 说明接口能通但字段格式变了。"
)

workflow = {
    "id": "local_diag_geo_0001",
    "name": "诊断-定位与逆地理",
    "triggers": [
        {"id": "trigger_manual", "indentationLevel": 0, "isDisabled": False,
         "moduleId": "vflow.trigger.manual", "parameters": {}}
    ],
    "steps": [
        {"id": "note", "indentationLevel": 0, "isDisabled": False,
         "moduleId": "vflow.data.comment", "parameters": {"content": NOTE}},
        {"id": "diag", "indentationLevel": 0, "isDisabled": False,
         "moduleId": "vflow.shizuku.shell_command",
         "parameters": {"mode": "auto", "command": DIAG_CMD, "__error_policy": "SKIP"}},
        {"id": "show", "indentationLevel": 0, "isDisabled": False,
         "moduleId": "vflow.data.quick_view",
         "parameters": {"content": "{{diag.result}}"}}
    ],
    "isEnabled": True,
    "isFavorite": False,
    "wasEnabledBeforePermissionsLost": False,
    "folderId": "1d7b47ea-181f-420f-bcfb-8226faec70c8",
    "order": 0,
    "cardIconRes": "rounded_layers_fill_24",
    "cardThemeColor": "#FF8A5B",
    "version": "1.0.0",
    "vFlowLevel": 1,
    "description": "定位与逆地理链路的只读诊断：逐段打印 dumpsys 匹配、坐标抽取、curl 可用性、逆地理接口响应，弹窗查看，用于定位「未知地区」的成因。",
    "author": "",
    "homepage": "",
    "tags": ["诊断", "定位", "调试"]
}

os.makedirs(os.path.dirname(DST), exist_ok=True)
with open(DST, 'w', encoding='utf-8') as f:
    json.dump(workflow, f, ensure_ascii=False, indent=2)

print("写入:", DST)
print("字节数:", os.path.getsize(DST))
d = json.load(open(DST, encoding='utf-8'))
print("JSON 合法，步骤:", [s["id"] for s in d["steps"]])
print("\n--- 回读命令 ---")
print(d["steps"][1]["parameters"]["command"])
