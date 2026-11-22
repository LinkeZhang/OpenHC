package io.github.gcbond.openhc.core.hisight.control

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.hisight.HiSightConfig
import io.github.gcbond.openhc.core.hisight.Vectors
import io.github.gcbond.openhc.core.hisight.arr
import io.github.gcbond.openhc.core.hisight.ascii
import io.github.gcbond.openhc.core.hisight.bool
import io.github.gcbond.openhc.core.hisight.configFrom
import io.github.gcbond.openhc.core.hisight.hex
import io.github.gcbond.openhc.core.hisight.int
import io.github.gcbond.openhc.core.hisight.obj
import io.github.gcbond.openhc.core.hisight.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 能力协商与 Transport 解析，对照参考实现在同样输入上的结果。 */
class CapabilitiesTest {
    private val section = Vectors.section("capabilities").jsonObject
    private val configs: Map<String, HiSightConfig> =
        section.obj("configs").mapValues { configFrom(it.value.jsonObject) }

    @Test
    fun getParameterResponsesMatchPrototype() {
        for (element in section.arr("get")) {
            val case = element.jsonObject
            val query = case.getValue("query").hex()
            for ((name, expected) in case.obj("results")) {
                val actual = try {
                    Capabilities(configs.getValue(name)).responseBody(query)
                } catch (e: MalformedMessageException) {
                    null
                }
                val want = (expected as? JsonPrimitive)?.takeUnless { expected is JsonNull }?.content
                assertEquals(want, actual, "配置 $name 查询 ${case.str("query")}")
            }
        }
    }

    @Test
    fun setParameterSelectionsMatchPrototype() {
        for (element in section.arr("set")) {
            val case = element.jsonObject
            val body = case.getValue("body").hex()
            for ((name, expected) in case.obj("results")) {
                val label = "配置 $name 正文 ${case.str("body")}"
                val caps = Capabilities(configs.getValue(name))
                val fields = try {
                    ParameterText.parameters(body)
                } catch (e: MalformedMessageException) {
                    assertEquals("parse-error", (expected as? JsonPrimitive)?.content, label)
                    continue
                }
                // 第五处故意差异：本端没声明的 `his_` 扩展字段（实测鸿蒙 4.2 的「智慧聆听」会带）。
                // 参考实现遇到未知名字直接整段解析失败，本实现识别但跳过；只要报文里还有别的字段，
                // 解析结果就会是「少了一些键」而不是解析失败，所以这里按「参考实现要 parse-error、
                // 本实现解析成功」放行。
                if (expected is JsonPrimitive && expected.content == "parse-error" &&
                    body.toString(Charsets.US_ASCII).split("\r\n").any { line ->
                        val name = line.substringBefore(':', "").trim().lowercase()
                        name.startsWith("his_") &&
                            name !in ParameterText.CAPABILITY_FIELDS &&
                            name !in ParameterText.METADATA_FIELDS &&
                            name != ParameterText.TRIGGER
                    }
                ) {
                    continue
                }
                if (ParameterText.TRIGGER in fields) {
                    assertEquals("trigger", (expected as? JsonPrimitive)?.content, label)
                    continue
                }
                try {
                    caps.select(fields)
                } catch (e: MalformedMessageException) {
                    assertEquals("rejected", (expected as? JsonPrimitive)?.content, label)
                    continue
                }
                // 与参考实现的第一处故意差异：参考实现只认 `his_audio_codecs: none`，本实现把这条字段整体忽略
                // （投屏层不声明也不处理音频，声音走 DMSDP 的音频通道）。鸿蒙 4.2 会在这里带回非 none 的值，
                // 参考实现因此拒绝、鸿蒙 4.2 连不上，所以这一处必须不同。
                if (expected is JsonPrimitive && expected.content == "rejected" &&
                    fields.keys == setOf("his_audio_codecs") && fields["his_audio_codecs"].orEmpty().lowercase() != "none"
                ) {
                    continue
                }
                // 第二处故意差异：能力列表的分隔符。参考实现要求逗号后必须跟一个空格，本实现按逗号切分、两端空白
                // 可有可无（鸿蒙 4.2 回传的就是 `a,b,c`，严格按参考实现会连不上）。
                if (expected is JsonPrimitive && expected.content == "rejected" &&
                    fields["his_uibc_capability"]?.let { it.contains(",") && !it.contains(", ") } == true
                ) {
                    continue
                }
                // 第四处故意差异：手机回传本端从未声明（或声明为空集合）的扩展能力
                // （his_media_capability / his_vtp / his_feature 的 input_feature_set）。
                // 它们只描述手机自己支持什么，本端只走普通 H.264 + 非 VTP、未实现任何可选输入功能，
                // 识别但不使用即可；参考实现一律拒绝，那会让鸿蒙 4.2 连不上。
                if (expected is JsonPrimitive && expected.content == "rejected" &&
                    fields.keys.isNotEmpty() &&
                    fields.keys.all {
                        it == "his_media_capability" || it == "his_vtp" || it == "his_feature"
                    }
                ) {
                    continue
                }
                // 第三处故意差异：能力列表里的重复项和鼠标事件。参考实现遇到重复项或未声明项一律拒绝，
                // 本实现去重、并忽略鼠标事件（投屏层不声明也不处理鼠标），其余未声明项仍然拒绝。
                if (expected is JsonPrimitive && expected.content == "rejected" &&
                    fields["his_uibc_capability"]?.let { uibc ->
                        val generic = uibc.substringAfter("generic_cap_list=", "").substringBefore(';')
                        val items = generic.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        val withoutMouse = items.filter { it !in setOf("mouse_down", "mouse_up", "mouse_move") }
                        val hasRepeat = withoutMouse.distinct().size != withoutMouse.size
                        val vendors = uibc.substringAfter("vendor_cap_list=", "").substringBefore(';')
                            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        hasRepeat || vendors.distinct().size != vendors.size
                    } == true
                ) {
                    continue
                }
                val result = expected as? JsonObject ?: throw AssertionError("$label：参考实现拒绝，本实现却接受了")
                val selected = result["selected"]
                if (selected is JsonObject) {
                    val s = caps.selection!!
                    assertEquals(selected.int("width"), s.width, label)
                    assertEquals(selected.int("height"), s.height, label)
                    assertEquals(selected.int("fps"), s.fps, label)
                } else {
                    assertNull(caps.selection, label)
                }
                val setup = try {
                    caps.mediaSetup(12345, 12346)
                } catch (e: MalformedMessageException) {
                    null
                }
                val expectedSetup = result["setup"]
                if (expectedSetup is JsonObject) {
                    requireNotNull(setup) { "$label：参考实现能完成 SETUP，本实现不能" }
                    assertEquals(expectedSetup.arr("events").map { it.jsonPrimitive.content }, setup.inputEvents, label)
                    assertEquals(expectedSetup.arr("vendors").map { it.jsonPrimitive.content }, setup.inputVendors, label)
                    assertEquals(expectedSetup["source"]?.jsonPrimitive?.intOrNull, setup.sourceDeviceType, label)
                } else {
                    assertNull(setup, label)
                }
                val noRemote = try {
                    caps.mediaSetup(12345, -1)
                    true
                } catch (e: MalformedMessageException) {
                    false
                }
                assertEquals(result.bool("setupNoRemote"), noRemote, label)
            }
        }
    }

    @Test
    fun transportMatchesPrototype() {
        for (element in section.arr("transport")) {
            val case = element.jsonObject
            val value = case.str("value")
            val actual = try {
                parseTransport(value)
            } catch (e: MalformedMessageException) {
                null
            }
            if (case.bool("ok")) {
                requireNotNull(actual) { "Transport 应当接受：$value" }
                assertEquals(case.int("server"), actual.serverPort, value)
                assertEquals(case.int("remote"), actual.remoteControlPort, value)
            } else {
                assertNull(actual, "Transport 应当拒绝：$value")
            }
        }
    }

    @Test
    fun failedSelectionKeepsPreviousResult() {
        val config = HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true)
        val caps = Capabilities(config)
        val good = "input_category_list=GENERIC;generic_cap_list=left_move, left_point_up, left_point_down;"
        caps.select(
            ParameterText.parameters(
                ascii("his_video_formats: codecs 1;width 320;height 240;fps 25\r\nhis_uibc_capability: $good\r\n"),
            ),
        )
        assertFailsWith<MalformedMessageException> {
            caps.select(
                ParameterText.parameters(
                    ascii(
                        "his_video_formats: codecs 1;width 640;height 480;fps 30\r\n" +
                            "his_uibc_capability: input_category_list=GENERIC;generic_cap_list=left_move;\r\n",
                    ),
                ),
            )
        }
        val setup = caps.mediaSetup(1000, 1001)
        assertEquals(320, setup.selection.width)
        assertEquals(listOf("left_move", "left_point_up", "left_point_down"), setup.inputEvents)
    }

    /**
     * 鸿蒙 4.2 回传的列表不带逗号后的空格（`a,b,c`），参考实现只认 `a, b, c`。
     * 分隔符宽松处理属于有意偏离，这里把几种写法都钉住，避免以后又改回严格模式。
     */
    @Test
    fun inputSelectionAcceptsListWithoutSpacesAfterCommas() {
        val config = HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true)
        val forms = listOf(
            "left_move,left_point_up,left_point_down",
            "left_move, left_point_up, left_point_down",
            "left_move , left_point_up ,left_point_down",
            "left_point_down,left_move,left_point_up",
            "left_move,left_point_up,left_point_down,",
        )
        for (form in forms) {
            val caps = Capabilities(config)
            caps.select(
                ParameterText.parameters(
                    ascii(
                        "his_video_formats: codecs 1;width 640;height 480;fps 30\r\n" +
                            "his_uibc_capability: input_category_list=GENERIC;generic_cap_list=$form;\r\n",
                    ),
                ),
            )
            val setup = caps.mediaSetup(1000, 1001)
            assertEquals(
                setOf("left_move", "left_point_up", "left_point_down"),
                setup.inputEvents.toSet(),
                form,
            )
        }
    }

    @Test
    fun inputSelectionStillRejectsUnknownEvents() {
        val config = HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true)
        for (form in listOf(
            "left_move,left_point_up,left_point_down,right_move",
            "left_move;left_point_up;left_point_down",
        )) {
            val caps = Capabilities(config)
            assertFailsWith<MalformedMessageException>(form) {
                caps.select(
                    ParameterText.parameters(
                        ascii(
                            "his_video_formats: codecs 1;width 640;height 480;fps 30\r\n" +
                                "his_uibc_capability: input_category_list=GENERIC;generic_cap_list=$form;\r\n",
                        ),
                    ),
                )
            }
        }
    }

    /**
     * 鸿蒙 4.2 实测回传：整份列表重复一遍，并且额外带上三个鼠标事件。
     * 这两种差异都不该让协商失败——重复项去重，鼠标事件本端本来就不处理。
     */
    @Test
    fun inputSelectionToleratesRepeatsAndMouseEvents() {
        val config = HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true, keyInput = true)
        val events = "left_point_down, left_point_up, left_move, left_key_down, left_key_up"
        val mouse = "mouse_down, mouse_up, mouse_move"
        val forms = listOf(
            "$events, $mouse",
            "$events, $mouse, $events",
            "$mouse, $events",
            "$events, $mouse, $events, $mouse",
        )
        for (form in forms) {
            val caps = Capabilities(config)
            caps.select(
                ParameterText.parameters(
                    ascii(
                        "his_video_formats: codecs 1;width 640;height 480;fps 30\r\n" +
                            "his_device_type: source_device_type 7;source_subtype 0;device_type 4;subtype 0;\r\n" +
                            "his_uibc_capability: input_category_list=GENERIC;generic_cap_list=$form;\r\n",
                    ),
                ),
            )
            val setup = caps.mediaSetup(1000, 1001)
            assertEquals(
                setOf(
                    "left_point_down", "left_point_up", "left_move",
                    "left_key_down", "left_key_up",
                ),
                setup.inputEvents.toSet(),
                form,
            )
        }
    }

    @Test
    fun malformedEchoedGopOrBitrateIsIgnored() {
        val config = HiSightConfig(640, 480, 640, 480, 30, 160, gop = 60)
        for (echo in listOf("gop -1", "gop abc", "gop", "gop 1 2", "bitrate -5", "gop 60;gop 61")) {
            val caps = Capabilities(config)
            caps.select(ParameterText.parameters(ascii("his_video_formats: codecs 1;$echo;width 320;height 240;fps 25\r\n")))
            val setup = caps.mediaSetup(1000, -1)
            assertEquals(320, setup.selection.width, echo)
            assertEquals(25, setup.selection.fps, echo)
        }
        // 本端要用的字段照旧严格：帧率不合规仍然拒绝。
        assertFailsWith<MalformedMessageException> {
            Capabilities(config).select(
                ParameterText.parameters(ascii("his_video_formats: codecs 1;gop 60;width 320;height 240;fps -1\r\n")),
            )
        }
    }

    @Test
    fun configRejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> { HiSightConfig(0, 480, 640, 480, 30, 160) }
        assertFailsWith<IllegalArgumentException> { HiSightConfig(640, 480, 640, 480, 30, 160, gop = 0) }
        assertFailsWith<IllegalArgumentException> { HiSightConfig(640, 480, 640, 480, 30, 160, textInput = true) }
        assertFailsWith<IllegalArgumentException> { HiSightConfig(640, 480, 640, 480, 30, 160, deviceType = 6) }
    }

    /**
     * 鸿蒙 4.2 在能力选择里会把音频编码能力带回来（非 none）。投屏层不声明、也不处理音频，
     * 这条字段不该让整个投屏失败；同一个选择里的其他字段照旧严格校验。
     */
    @Test
    fun audioCodecsFromThePhoneAreIgnored() {
        val config = configs.getValue("plain")
        val caps = Capabilities(config)
        caps.select(ParameterText.parameters(ascii("his_audio_codecs: 0\r\n")))
        assertNull(caps.selection, "只有音频字段时不影响视频选择")

        val withVideo = Capabilities(config)
        withVideo.select(
            ParameterText.parameters(
                ascii("his_audio_codecs: 0\r\nhis_video_formats: codecs 1;width 640;height 480;fps 30\r\n"),
            ),
        )
        assertEquals(640, withVideo.selection?.width, "音频字段不影响视频参数")

        // 音频字段之外仍然严格：视频参数不合规照样拒绝。
        assertFailsWith<MalformedMessageException> {
            Capabilities(config).select(
                ParameterText.parameters(
                    ascii("his_audio_codecs: 0\r\nhis_video_formats: codecs 1;width 99999;height 480;fps 30\r\n"),
                ),
            )
        }
    }

    /**
     * 手机回传本端从未声明（或声明为空集合）的扩展能力，实测鸿蒙 4.2 三处都会带：
     * `his_media_capability`、`his_vtp`、`his_feature: input_feature_set=1, 101, …`。
     * 它们只描述手机自己支持什么，不改变本端要处理的事件集合，识别但不使用即可。
     */
    @Test
    fun undeclaredExtendedCapabilitiesFromThePhoneAreIgnored() {
        val config = configs.getValue("plain")
        val crlf = "\r\n"
        for (extra in listOf(
            "his_media_capability: 1$crlf",
            "his_media_capability: none$crlf",
            "his_vtp: supported$crlf",
            "his_feature: input_feature_set=1, 101, 102, 103, 104, 105, 204, 301;$crlf",
            "his_media_capability: 1$crlf" + "his_vtp: supported$crlf" +
                "his_feature: input_feature_set=1, 101, 102, 103, 104, 105, 204, 301;$crlf",
        )) {
            val caps = Capabilities(config)
            caps.select(
                ParameterText.parameters(
                    ascii(extra + "his_video_formats: codecs 1;width 640;height 480;fps 30$crlf"),
                ),
            )
            assertEquals(640, caps.selection?.width, extra)
            assertEquals(480, caps.selection?.height, extra)
        }
    }

    /**
     * 忽略这几条字段不等于放松整体校验：真正的协商内容仍然严格。
     * 视频参数超范围、输入事件缺项，照样拒绝。
     */
    @Test
    fun ignoredFieldsDoNotWeakenRealNegotiation() {
        val config = configs.getValue("touch")
        val crlf = "\r\n"
        val feature = "his_feature: input_feature_set=1, 101;$crlf"

        // 视频尺寸超出声明范围：拒绝。
        assertFailsWith<MalformedMessageException> {
            Capabilities(config).select(
                ParameterText.parameters(
                    ascii(feature + "his_video_formats: codecs 1;width 99999;height 480;fps 30$crlf"),
                ),
            )
        }
        // 输入事件缺了必需项：拒绝。
        assertFailsWith<MalformedMessageException> {
            Capabilities(config).select(
                ParameterText.parameters(
                    ascii(
                        feature + "his_video_formats: codecs 1;width 640;height 480;fps 30$crlf" +
                            "his_uibc_capability: input_category_list=GENERIC;generic_cap_list=left_move;$crlf",
                    ),
                ),
            )
        }
    }

    /**
     * 鸿蒙 4.2 的「智慧聆听（连续对话）」会在能力选择里附带本端没声明的 `his_` 扩展字段。
     * 这类字段识别但跳过，不能因此整段解析失败（否则扫码后一秒就断开）。
     */
    @Test
    fun unknownHisExtensionFieldsAreSkippedNotRejected() {
        val config = configs.getValue("touch")
        val crlf = "\r\n"
        val fields = ParameterText.parameters(
            ascii(
                "his_version: 1.0$crlf" +
                    "his_video_formats: codecs 1;width 640;height 480;fps 30$crlf" +
                    "his_audio_listen: 1$crlf" +
                    "his_continuous_dialog: enabled$crlf" +
                    "his_uibc_capability: input_category_list=GENERIC;" +
                    "generic_cap_list=left_move, left_point_up, left_point_down;$crlf",
            ),
        )
        assertEquals(
            setOf("his_version", "his_video_formats", "his_uibc_capability"),
            fields.keys,
            "本端没声明的 his_ 扩展字段应当被跳过",
        )
        val caps = Capabilities(config)
        caps.select(fields)
        assertEquals(640, caps.selection?.width)

        // 不是 his_ 开头的未知名字仍然拒绝：那是真拆歪了。
        assertFailsWith<MalformedMessageException> {
            ParameterText.parameters(ascii("something_else: 1$crlf"))
        }
        // 缺冒号的行仍然拒绝。
        assertFailsWith<MalformedMessageException> {
            ParameterText.parameters(ascii("his_version 1.0$crlf"))
        }
    }
}
