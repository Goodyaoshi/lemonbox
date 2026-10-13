package com.goodyaoshi.lemonbox.data.imagecloud

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 图片来源决策（F20）。
 *
 * 这条判断决定「本地有就用本地、本地没了才去云端」，写错的表现是
 * 「新设备上图片一直空着」或「明明本地有图却去联网取」，都很难从界面看出原因，
 * 因此把决策本身单独锁住。它刻意不碰文件系统——是否可用由参数传入。
 */
class ImageLocationDecisionTest {

    // ---------- 本地优先 ----------

    @Test
    fun localFileAvailable_prefersLocalEvenWhenObjectKeyExists() {
        // 关键：本地能看就绝不联网。这既是省流量，也是保证离线可用。
        val location = decideImageLocation(
            localPath = "/data/.../item_1.jpg",
            localAvailable = true,
            objectKey = "lemonbox/images/abc.jpg"
        )

        assertEquals(ImageLocation.Local("/data/.../item_1.jpg"), location)
    }

    @Test
    fun localTrimsWhitespaceBeforeUse() {
        val location = decideImageLocation(
            localPath = "  /data/.../item_1.jpg  ",
            localAvailable = true,
            objectKey = null
        )

        assertEquals(ImageLocation.Local("/data/.../item_1.jpg"), location)
    }

    // ---------- 本地缺失则回退云端 ----------

    @Test
    fun localMissing_fallsBackToCloudWhenObjectKeyPresent() {
        val location = decideImageLocation(
            localPath = "/data/.../item_1.jpg",
            localAvailable = false,
            objectKey = "lemonbox/images/abc.jpg"
        )

        assertEquals(ImageLocation.Cloud("lemonbox/images/abc.jpg"), location)
    }

    @Test
    fun blankLocalPath_usesCloud() {
        // 换设备后只同步了数据、还没有图片文件时就是这样：路径为空但有对象键。
        val location = decideImageLocation(
            localPath = null,
            localAvailable = false,
            objectKey = "lemonbox/images/abc.jpg"
        )

        assertEquals(ImageLocation.Cloud("lemonbox/images/abc.jpg"), location)
    }

    @Test
    fun objectKeyTrimsWhitespace() {
        val location = decideImageLocation(
            localPath = "",
            localAvailable = false,
            objectKey = "  lemonbox/images/abc.jpg "
        )

        assertEquals(ImageLocation.Cloud("lemonbox/images/abc.jpg"), location)
    }

    // ---------- 兜底与边界 ----------

    /**
     * 本地文件没了、也没有对象键：仍返回本地路径。
     * 让加载器走错误/占位分支，比返回 null 多保留了「这张图本来该在哪」的信息，
     * 排查时更有用。
     */
    @Test
    fun localMissingAndNoObjectKey_fallsBackToLocalPathForDiagnosability() {
        val location = decideImageLocation(
            localPath = "/data/.../item_1.jpg",
            localAvailable = false,
            objectKey = null
        )

        assertEquals(ImageLocation.Local("/data/.../item_1.jpg"), location)
    }

    @Test
    fun localMissingAndBlankObjectKey_fallsBackToLocalPath() {
        val location = decideImageLocation(
            localPath = "/data/.../item_1.jpg",
            localAvailable = false,
            objectKey = "   "
        )

        assertEquals(ImageLocation.Local("/data/.../item_1.jpg"), location)
    }

    @Test
    fun nothingUsable_yieldsNone() {
        assertEquals(ImageLocation.None, decideImageLocation(null, false, null))
        assertEquals(ImageLocation.None, decideImageLocation("", false, ""))
        assertEquals(ImageLocation.None, decideImageLocation("   ", false, "   "))
    }

    /**
     * 本地「可用」但没有路径（理论上的不一致输入）不该被当成可用，
     * 否则会返回一个空路径让加载器去解析，产生难查的报错。
     */
    @Test
    fun localAvailableButBlankPath_doesNotProduceEmptyLocal() {
        assertEquals(
            ImageLocation.None,
            decideImageLocation(localPath = "   ", localAvailable = true, objectKey = null)
        )
        assertEquals(
            ImageLocation.Cloud("k.jpg"),
            decideImageLocation(localPath = "", localAvailable = true, objectKey = "k.jpg")
        )
    }
}
