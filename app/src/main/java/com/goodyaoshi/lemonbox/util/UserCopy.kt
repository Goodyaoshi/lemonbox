package com.goodyaoshi.lemonbox.util

/**
 * 对「她」的称呼的唯一来源。
 *
 * 背景：「柠檬百宝箱」的意思就是「给柠檬做的百宝箱」，这个 App 只做给她一个人用。
 * 「柠檬」既是她的昵称，也是产品名的一半，两处有意重叠，属于产品设定，不必回避。
 *
 * 使用建议：
 * - 称呼位、问候、通知、投屏设备名等「对她说话」的地方，都可以放心用 [NICKNAME]；
 * - 面向操作的短提示（校验、错误、按钮说明）仍建议用第二人称「你」，
 *   像「柠檬确认一下 Wi-Fi 是否已连接」这类句子读起来别扭——这是语感建议，不是硬性分区。
 *
 * 运行时的当前称呼以 [com.goodyaoshi.lemonbox.data.settings.AppPreferences.nickname] 为准，
 * 本常量只作为出厂默认值。
 */
object UserCopy {

    /** 她的默认昵称（出厂值，可在「我的」页修改）。 */
    const val NICKNAME = "柠檬"
}
