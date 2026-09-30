# vFlow 项目长期笔记

> 只记跨会话有长期价值的内容。日常过程写进 `YYYY-MM-DD.md`。

## 项目性质

Android 应用（不是固件工程）。Kotlin 2.3.20 + AGP 9.0.1 + Gradle 9.2.1，compileSdk/targetSdk 36，
minSdk 29，`versionCode 49 / versionName 1.5.3-pr1`。
Gradle 模块：`:app`（本体）+ `:core`（服务端逻辑，用 R8 的 D8 单独编成
`app/src/main/assets/vFlowCore.dex`，由 `:core:buildDex` 任务产出，app 的 `preBuild` 依赖它）。
`namespace = com.chaomixian.vflow`，目录 `app/src/main/java/com/chaomixian/vflow/`。
内含 CMake 原生代码（`app/src/main/cpp/`，ppocr_v5 + OpenCV），因此必须装 NDK + CMake。
`app/build.gradle.kts` 里 `splits.abi` 开了 4 个 ABI 且 `isUniversalApk = true` → 一次构建出 5 个 APK。

## 本机构建环境（已探明）

| 项 | 值 |
|---|---|
| ANDROID_HOME | `D:\Android`（就是 SDK 根，含 platforms/build-tools/ndk/cmake） |
| platforms | android-34 / 35 / 36 |
| build-tools | 34.0.0 / 35.0.0 / 36.0.0 |
| NDK | 28.2.13676358 |
| CMake | 3.22.1 |
| JDK | 17.0.12 |
| 系统 Gradle | `D:\Android\Gradle\gradle-8.9`（**不要用**，AGP 9 需要 Gradle 9.x） |

## 编译步骤（可直接复用）

```bash
# 1) 首次需要 local.properties（已被 .gitignore 忽略）
#    sdk.dir=D:/Android
#    :core 模块也会读这个文件拿 android.jar 路径

# 2) 必须清掉代理环境变量，否则 Gradle 下载依赖会失败
cd /d/work/code/vFlow && env -u HTTP_PROXY -u HTTPS_PROXY -u http_proxy \
  -u https_proxy -u ALL_PROXY -u all_proxy \
  ./gradlew assembleDebug --no-configuration-cache --console=plain
```

- 只出 debug APK：`assembleDebug`。**不要用 assembleRelease** ——
  `signing.properties` / `vFlow.jks` 不在仓库里（`.gitignore` 排除），release 没有签名配置。
- `--no-configuration-cache`：`gradle.properties` 里 `org.gradle.configuration-cache=true`，
  配自定义 Task（`:core` 的 BuildDexTask 等）容易出问题，编译时关掉更稳。
- 耗时：首次约 7.5 分钟（含下载 Gradle 9.2.1 + 依赖 + 4 个 ABI 原生构建）；
  缓存后约 1 分 20 秒。
- 产物：`app/build/outputs/apk/debug/app-{arm64-v8a,armeabi-v7a,x86,x86_64,universal}-debug.apk`
  （arm64 约 196MB，universal 约 475MB）。**真机装 `app-arm64-v8a-debug.apk`。**
  签名用仓库根目录的 `debug.keystore`（口令 android / androiddebugkey / android）。

## 已知构建故障

**`:app:mergeDebugJavaResource` 失败**，报
`java.io.FileNotFoundException: ...\intermediates\incremental\debug-mergeJavaRes\zip-cache\<base64> (拒绝访问。)`
—— Windows 上增量 zip-cache 被占用/杀软扫描导致，与代码无关（Kotlin/CMake/dex 都已通过）。
处理：
```bash
./gradlew --stop
rm -rf app/build/intermediates/incremental/debug-mergeJavaRes
# 重跑 assembleDebug
```
不要用 `./gradlew clean`，会连 CMake 产物一起清掉，白白多花几分钟。

## 其他约定

- `core/workflow/module/TODO.md` **严重滞后于代码**，判断功能缺口不要信它，
  以 `grep -rhoP 'override val id = "\K[^"]+'` 抽出的实际模块 id 为准。
- AndroidManifest 的 `<queries>` 里已补 `android.intent.action.TTS_SERVICE`（TTS 模块需要）。

## Shell 模块的两个坑（写工作流必看）

`vflow.shizuku.shell_command` 输出字段：`result`（命令输出）/ `success` / `exit_code`。
执行方式 `mode` 的语义：`auto` **不是**「自动降级尝试」，而是**读全局偏好**
`vFlowPrefs.default_shell_mode`（默认 `"shizuku"`），可选 shizuku / root / core / core_root
（见 `ShellManager.execShellCommandWithResult`）。所以 `mode: auto` 默认走 Shizuku，
需要 Shizuku 已激活并授权。

**坑 1 —— 空输出会被替换成英文提示语。**
`ShizukuUserService.execWithStructuredResult` 里：
```kotlin
exitCode == 0 -> output = if (stdout.isNotEmpty()) stdout else "Command executed successfully"
```
所以「命令跑完但没有任何输出」时，`{{xxx.result}}` 拿到的是字符串
`Command executed successfully`，不是空串。典型触发场景：管道最后一段是 `sed`，
前面 `grep` 没匹配到内容 → 管道退出码仍是 0、stdout 为空 → 变量被污染。
写工作流时要么在命令末尾补 `| grep .` 之类保证有输出，要么下游先判断内容。

**坑 2 —— 输出不会带尾部换行。**
`readStream()` 用 `readLine()` 逐行读再以 `\n` 连接，末行换行被吃掉，
所以 `{{xxx.result}}` 可以安全地直接拼进 URL。多行输出则是 `行1\n行2`。

## 魔法变量与 JSON 解析的边界（写工作流前必看）

- `parse_json` 的 `path` 支持点号路径与数组下标，如 `current_condition.0.weatherDesc.0.value`，
  也支持通配 `items.*.id`（结果进 `all_values` 列表）。
- **`parse_json` 的 `first_value` 取到对象时不是字典，而是被整段转成字符串。**
  原因：`parse_json` 把 `org.json.JSONObject/JSONArray` 原样交给 `VObjectFactory.from()`，
  而该函数的 `when` 只处理 `Map/Collection/Array/基础类型`，**没有 JSONObject 分支**，
  于是落到 `else -> VString(value.toString())`。所以
  「先取 `current_condition.0` 再写 `{{x.first_value.weatherDesc.0.value}}`」这种钻属性的写法**不可行**。
- 想在一个步骤里取多个字段，只有 JS 模块（`vflow.system.js`）可行：
  它的返回值经 `JsValueConverter.coerceToKotlin` 变成普通 Kotlin Map，
  再 `VDictionary(vMap)` → 是真正的字典，`{{stepId.outputs.字段}}` 可正常钻取。
  JS 脚本约定：**最后一个表达式的值就是返回值**（默认脚本以 `r;` 结尾），
  `inputs` 参数是一个字典（脚本内用 `inputs.xxx` 取），注意 `script` 本身**不会**做变量解析，
  要拿工作流变量必须走 `inputs`。该 `inputs` 字典参数的序列化格式未实测（仓库内无示例工作流），
  用在无人值守的工作流上有风险，建议先在编辑器里试跑。

## 错误处理策略参数

步骤参数里以 `__` 开头的是框架保留字段，见 `ActionStepExecutionSettings`：
`__error_policy` ∈ `STOP`（默认）/ `SKIP` / `RETRY`，配 `__retry_count`、`__retry_interval`（毫秒）。
设置 `SKIP` 后该步失败不中断工作流，缺失输出会被填成 `VNull`，`asString()` 为**空串**
（`VNull.asString() = ""`），所以下游提示词里会看到空值而不是 "null"。

## 变量解析的边界（写工作流必看）

- 引用语法 `{{stepId.outputId.属性...}}`，`stepId` 就是步骤 id 原文（可为 UUID）。
  查的是 `context.stepOutputs[stepId][outputId]`（`WorkflowExecutor.kt:600` 写入），
  与步骤的**输入参数**（`parameters`）是两套独立存储 —— 输入 id 与输出 id 同名**不冲突**。
- **步骤被 SKIP 时**：该步所有输出定义填 `VNull` → `asString()` 为空串。
- **变量完全没命中时**（步骤没执行 / 没定义该输出）：`resolveVariableObject` 退化为
  `VObjectFactory.from("{表达式}")`，即输出原样字符串，形如
  `{b43bd1cf-....latitude}`（**单层花括号**，不是 `{{}}`）。
  所以 shell/URL 里引用变量后**必须先用正则校验**再使用，不能假定是干净数字。
- `VNumber.asString()`：Double 有小数部分 → `raw.toString()`（如 `39.9042123456789`）；
  恰好是整数 → 去掉小数点。
- 变量解析是**递归**的（最多 10 层），解析结果里若还含 `{{}}` 会继续展开。

## 取设备当前位置

vFlow 里**唯一**能拿当前经纬度的模块是 `vflow.system.location_range_check`（显示名「位置范围判断」）。
注意它的输入 `latitude` / `longitude` / `radius` 是**目标点 + 半径**（用于判断"是否在范围内"），
**不是**取到的坐标；当前坐标在输出里：`latitude` / `longitude` / `accuracy`(米) /
`distance`(米) / `inside`(BOOL，是否在目标半径内)。

用它取坐标时**必须把 `__error_policy` 设成 `SKIP`** —— 默认 `STOP` 会在定位失败
（无权限 / 定位开关关 / 4 秒超时）时直接中断整个工作流。

坐标系是 WGS-84（`LocationManager` 原生）。对接 BigDataCloud / wttr.in 这类国外接口无需转换；
**接高德 / 腾讯 / 百度必须做 WGS-84 → GCJ-02 / BD-09 转换**，否则偏移数百米。
只给「模糊定位」（`ACCESS_COARSE_LOCATION`）时 `accuracy` 可能到公里级，逆地理会报错区县，
可用 `{{stepId.accuracy}}` 加阈值判断。

## 网络接口可达性（2026-09-30 清代理实测）

**国外接口在国内网络基本不可达，写工作流别用**：
`api.bigdatacloud.net`（逆地理）、`nominatim.openstreetmap.org` → 均 HTTP 000 / 15s 超时。
`wttr.in` 通（0.7~2.4s）但**有抖动**（偶发 20s 超时），且 `lang=zh` 中文翻译**已失效**——
`current_condition[0].weatherDesc` 与 `lang_zh` 都返回英文（`zh` / `zh-cn` 一样）；
`nearest_area` 只给英文拼音地名、**无区级**，不能替代逆地理。

**国内可用（无需 key）**：
- **逆地理编码**（推荐）：`https://www.amap.com/service/regeo?longitude=<lon>&latitude=<lat>`
  → `data.{province, city, district, desc, pos}`。高德网页版内部接口，0.1s、无 key、无限流，
  不带 UA/Referer 也能通；直辖市 `city` 不为空（北京市/上海市）；境外坐标各字段为空串。
  ⚠️ 输入要求 **GCJ-02**，设备 WGS-84 直传偏移 300~600 米（市级无影响）。
- **IP 定位**（仅省市、无区）：`https://api.bilibili.com/x/web-interface/zone`
  → `data.{province, city}`。0.2s、无 key，适合做逆地理失败的兜底。

需 key 但连通性好：`restapi.amap.com` / `apis.map.qq.com` / `api.map.baidu.com`。
其中百度 `reverse_geocoding/v3` 支持 `coordtype=wgs84ll` **直传 WGS-84**，省掉手动转 GCJ-02。

## Shell 命令里嵌变量的两个写法坑（实测踩过）

- 用 `echo "$V" | grep -qE ...` 校验数字时，若 `$V` 可能是负数，`echo "-1.2"` 会被当选项解析。
  加前缀锚点规避：`echo "X$V" | grep -qE '^X-?[0-9]+(\.[0-9]+)?$'`。
- **不要把正则拆成 `"$A,$B"` 拼接** —— 中间的 `$`（行尾锚）会让它**永远匹配不上**。
  分两次 grep，或用 `case` 的 `*[!0-9.,-]*` 反向排除非法字符。

## 工作流之间传参 / 取返回值（写子工作流必看）

`vflow.logic.call_workflow`（调用工作流）的输入 `inputs` **在 UIProvider 里根本没实现**
（`CallWorkflowModuleUIProvider.getHandledInputIds() = setOf("workflow_id")`，
`readFromEditor` 也只返回 `workflow_id`），所以**不能通过它传参**。实际靠**共享命名变量**：

- `WorkflowExecutor.executeSubWorkflow` 用
  `parentContext.copy(allSteps = ..., workflowStack = ...)`，**没有覆盖 `namedVariables`
  和 `stepOutputs`** —— data class 的 copy 是浅拷贝，这两个 MutableMap 是**父子同一个引用**。
  → 父工作流用「创建变量」写的命名变量，子工作流直接 `[[名字]]` 就能读；
  → 子工作流的 `stepOutputs[stepId]`，父工作流的变量解析也能命中。
- 「调用工作流」的输出：`result`（子工作流「停止并返回」的值）
  + `var_<变量名>`（`getDynamicOutputs` 扫描子工作流里所有 `vflow.variable.create` 收集）。

**坑：`vflow.variable.create` 和 `vflow.logic.return` 都不解析模板变量。**
两者都是 `context.getVariable("value")` 直接取值，而 `variables` 只是
`step.parameters.mapValues { VObjectFactory.from(it) }`（**原样包装、不 resolve**）。
所以 `创建变量 X = {{a.b}}` 存进命名变量的是**字面量** `{{a.b}}`。
能跑通是因为 `VariableResolver.resolve` 是**递归**的（最多 10 层），下游读到会再解析一层；
跨工作流时依赖 `stepOutputs` 共享才能解析到子工作流的步骤输出。
**可靠但隐式** —— 改这块要留意，建议在子工作流里加格式自校验做兜底。

**其他相关事实**：
- 导入时 `WorkflowJsonImportParser` 优先用 JSON 里的 `id`，为空才生成新的；
  `WorkflowImportHelper` 只对 folder 重生成 id → **`workflow_id` 可以写死**，
  且同 id 重复导入是**覆盖**不是新增。
- `Workflow` 数据类里**没有** inputs 字段，工作流没有形式化的「输入参数」概念。
- 无触发器的工作流导入时会被 `WorkflowNormalizer`（`ensureTrigger=true`）自动补一个手动触发器。
- `vflow.data.text_split`（文本分割）：输入 `text` + `delimiter`，输出 `result`(LIST)；
  `VList.getProperty` 支持数字索引，`{{step.result.0}}` / `{{step.result.-1}}` 可用。
- `vflow.trigger.manual` 的标准形态：`ActionStep(moduleId = "vflow.trigger.manual", parameters = emptyMap())`。
