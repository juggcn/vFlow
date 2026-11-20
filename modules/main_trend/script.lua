-- ============================================================
-- 主趋势线监控（vFlow 脚本模块）
--
-- 通达信原式：
--   主趋势线: EMA(EMA(CLOSE,10),10)
--   红(买) = 主趋势线 > REF(主趋势线,1)
--   绿(卖) = 其余（含持平）
-- 只取这一条线，其余 HG 变量不参与。
-- ============================================================

local BARS = 150          -- 参与计算的 K 线根数
local EMA_N = 10          -- EMA 周期，与通达信一致
local MAX_CODES = 30      -- 单次最多监控多少个标的

local period = tostring(inputs.period or "日线")
local sourceMode = tostring(inputs.source or "auto")
local rawCodes = tostring(inputs.codes or "")

-- 「仅输出变化标的」：boolean 或字符串 / 数字形式都认
local rawOnlyChanged = inputs.only_changed
local onlyChanged = (rawOnlyChanged == true or rawOnlyChanged == 1
    or tostring(rawOnlyChanged) == "true" or tostring(rawOnlyChanged) == "1")

-- 下拉框显示中文，脚本内部统一用 "5"/"15"/"30"/"60"/"day"/"week"；
-- 同时兼容旧工作流里存的 "day"/"week" 原始值。
local PERIOD_MAP = {
    ["5分钟"] = "5", ["15分钟"] = "15", ["30分钟"] = "30", ["60分钟"] = "60",
    ["日线"] = "day", ["周线"] = "week",
    ["5"] = "5", ["15"] = "15", ["30"] = "30", ["60"] = "60",
    ["day"] = "day", ["week"] = "week"
}
period = PERIOD_MAP[period] or "day"

local isMinute = (period ~= "day" and period ~= "week")

-- ---------- 工具 ----------

-- 代码规范化：补 sh/sz/bj 前缀
local function normCode(s)
    s = string.lower(s)
    if string.sub(s, 1, 2) == "sh" or string.sub(s, 1, 2) == "sz" or string.sub(s, 1, 2) == "bj" then
        return s
    end
    if string.len(s) ~= 6 or string.find(s, "%D") then
        return ""
    end
    local h = string.sub(s, 1, 1)
    if h == "6" or h == "5" or h == "9" then return "sh" .. s end
    if h == "4" or h == "8" then return "bj" .. s end
    return "sz" .. s
end

-- 输出数值统一保留两位小数（四舍五入）
local function round2(v)
    if v == nil then return 0 end
    return math.floor(v * 100 + 0.5) / 100
end

-- 通达信 EMA：首值取第一根，其后 alpha = 2/(N+1)
local function ema(vals, n)
    local out = {}
    local a = 2.0 / (n + 1)
    out[1] = vals[1]
    for i = 2, #vals do
        out[i] = out[i - 1] + a * (vals[i] - out[i - 1])
    end
    return out
end

local function httpGet(url, headers)
    local req = { url = url, method = "GET", timeout = 10 }
    if headers then req.headers = headers end
    local resp = vflow.network.http_request(req)
    return tostring(resp.response_body)
end

-- ---------- 三个数据源 ----------
-- 每个返回 closes(表), name(字符串)；失败时 error()
-- 说明：腾讯 K 线里的股票名是 \uXXXX 转义形式，luaj 的字符串是 UTF-16 语义，
--       按字节还原会乱码，所以腾讯一律不取名，显示代码；东财自带 UTF-8 名称。

local function loadTencent(code, p)
    local url, key
    if p ~= "day" and p ~= "week" then
        -- 分钟档（5/15/30/60）走 mkline，且不复权
        key = "m" .. p
        url = "https://ifzq.gtimg.cn/appstock/app/kline/mkline?param=" .. code .. "," .. key .. ",," .. BARS
    elseif p == "week" then
        url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=" .. code .. ",week,,," .. BARS .. ",qfq"
        key = "qfqweek"
    else
        url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=" .. code .. ",day,,," .. BARS .. ",qfq"
        key = "qfqday"
    end
    local body = httpGet(url)
    if not string.find(body, '"' .. key .. '"', 1, true) then
        error("腾讯无 " .. key)
    end
    local closes = {}
    for c in string.gmatch(body, '%["[%d%-]+","[-%d%.]+","([-%d%.]+)"') do
        table.insert(closes, tonumber(c))
    end
    return closes, ""
end

local function loadEastmoney(code, p)
    local secid = (string.sub(code, 1, 2) == "sh") and ("1." .. string.sub(code, 3)) or ("0." .. string.sub(code, 3))
    local klt = 101
    if p == "week" then
        klt = 102
    elseif p ~= "day" then
        klt = tonumber(p) or 101
    end
    local url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=" .. secid
        .. "&ut=fa5fd1943c7b386f172d6893dbfba10b&klt=" .. klt .. "&fqt=1&end=20500101&lmt=" .. BARS
        .. "&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58"
    local body = httpGet(url, {
        ["User-Agent"] = "Mozilla/5.0",
        ["Referer"] = "https://quote.eastmoney.com/"
    })
    if not string.find(body, '"klines"', 1, true) then
        error("东财无 klines")
    end
    local closes = {}
    for c in string.gmatch(body, '"[%d%- :]+,[-%d%.]+,([-%d%.]+),') do
        table.insert(closes, tonumber(c))
    end
    local name = string.match(body, '"name":"([^"]*)"') or ""
    return closes, name
end

local function loadSina(code, p)
    local scale = 240
    if p == "week" then
        scale = 1200
    elseif p ~= "day" then
        scale = tonumber(p) or 240
    end
    local url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData?symbol="
        .. code .. "&scale=" .. scale .. "&ma=no&datalen=" .. BARS
    local body = httpGet(url)
    if string.sub(body, 1, 1) ~= "[" then
        error("新浪无数据")
    end
    local closes = {}
    for c in string.gmatch(body, '"close":"([-%d%.]+)"') do
        table.insert(closes, tonumber(c))
    end
    return closes, ""
end

-- ---------- 解析标的列表 ----------
rawCodes = string.gsub(rawCodes, "，", ",")
rawCodes = string.gsub(rawCodes, "；", ";")
rawCodes = string.gsub(rawCodes, "、", ",")

local codes = {}
local seen = {}
for token in string.gmatch(rawCodes, "[^%s,;]+") do
    local c = normCode(token)
    if c ~= "" and not seen[c] then
        seen[c] = true
        table.insert(codes, c)
    end
end

local failed = {}
local summaryLines = {}
local greenToRed, redToGreen, details = {}, {}, {}
local status = "no_change"
local usedSource = ""

if #codes == 0 then
    return {
        status = "no_change",
        has_green_to_red = false,
        has_red_to_green = false,
        green_to_red = {},
        red_to_green = {},
        details = {},
        summary = "未填写监控标的",
        source = "",
        failed = "codes 为空"
    }
end

if #codes > MAX_CODES then
    for i = MAX_CODES + 1, #codes do
        table.insert(failed, codes[i] .. "(超出单次上限 " .. MAX_CODES .. ")")
    end
    local trimmed = {}
    for i = 1, MAX_CODES do trimmed[i] = codes[i] end
    codes = trimmed
end

-- ---------- 数据源顺序 ----------
local loaders = { tencent = loadTencent, eastmoney = loadEastmoney, sina = loadSina }
local sourceNames = { tencent = "腾讯", eastmoney = "东方财富", sina = "新浪" }

local order
if sourceMode == "tencent" then
    order = { "tencent" }
elseif sourceMode == "eastmoney" then
    order = { "eastmoney" }
elseif sourceMode == "sina" then
    order = { "sina" }
elseif isMinute then
    -- 所有分钟档：腾讯 mkline 不复权，东财支持前复权，优先东财
    order = { "eastmoney", "tencent", "sina" }
else
    order = { "tencent", "eastmoney", "sina" }
end

-- ---------- 逐标的计算 ----------
for _, code in ipairs(codes) do
    local closes, name, src = nil, nil, ""
    local lastErr = "未知错误"

    for _, key in ipairs(order) do
        local ok, result, gotName = pcall(loaders[key], code, period)
        if ok and result and #result >= EMA_N + 3 then
            closes, name, src = result, gotName, sourceNames[key]
            break
        elseif not ok then
            lastErr = tostring(result)
        else
            lastErr = "数据不足"
        end
    end

    if not closes then
        table.insert(failed, code .. "(" .. lastErr .. ")")
    else
        if usedSource == "" then usedSource = src end

        local e1 = ema(closes, EMA_N)
        local e2 = ema(e1, EMA_N)
        local n = #e2
        local cur = e2[n]
        local prev = e2[n - 1]
        local prev2 = e2[n - 2]

        local isUp = cur > prev
        local wasUp = prev > prev2

        local change = "no_change"
        if isUp and not wasUp then
            change = "green_to_red"
        elseif (not isUp) and wasUp then
            change = "red_to_green"
        end

        local label = "无变化"
        if change == "green_to_red" then label = "绿转红" elseif change == "red_to_green" then label = "红转绿" end

        local display = code
        if name ~= nil and name ~= "" then display = code .. " " .. name end

        if change == "green_to_red" then table.insert(greenToRed, display) end
        if change == "red_to_green" then table.insert(redToGreen, display) end

        -- 开了「仅输出变化标的」时，无变化的标的不进明细与汇总；
        -- 取数失败的仍照常列进 failed，不受此开关影响。
        if (not onlyChanged) or change ~= "no_change" then
            table.insert(details, {
                code = code,
                name = name or "",
                close = round2(closes[#closes]),
                trend = round2(cur),
                prev = round2(prev),
                direction = isUp and "up" or "down",
                change = change
            })

            table.insert(summaryLines, string.format("%s  收盘 %.2f  主趋势线 %.2f(%s)  %s",
                display, round2(closes[#closes]), round2(cur), isUp and "↑" or "↓", label))
        end
    end
end

-- ---------- 汇总 ----------
if #greenToRed > 0 and #redToGreen > 0 then
    status = "mixed"
elseif #greenToRed > 0 then
    status = "green_to_red"
elseif #redToGreen > 0 then
    status = "red_to_green"
else
    status = "no_change"
end

local summaryText = table.concat(summaryLines, "\n")
-- 开了过滤又一条变化都没有时，给一句说明，免得下游收到空正文
if summaryText == "" and onlyChanged and #failed == 0 then
    summaryText = "共 " .. #codes .. " 个标的，均无变化"
end
if #failed > 0 then
    if summaryText ~= "" then summaryText = summaryText .. "\n" end
    summaryText = summaryText .. "取数失败：" .. table.concat(failed, "，")
end

return {
    status = status,
    has_green_to_red = (#greenToRed > 0),
    has_red_to_green = (#redToGreen > 0),
    green_to_red = greenToRed,
    red_to_green = redToGreen,
    details = details,
    summary = summaryText,
    source = usedSource,
    failed = table.concat(failed, "，")
}
