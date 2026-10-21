package com.goodyaoshi.lemonbox.data.imagecloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云同步的内部开关。
 *
 * **这里不再有用户可见的开关。** 产品约定是「登录即开启云同步」：
 * 数据随登录自动双向同步，图片随保存与后台任务自动补传，退出登录即停止。
 * 因此四个开关恒为 true，只作为调度层的统一判断点保留——图片与文字仍走
 * 各自的后台任务，需要知道「该不该跑」，但答案只取决于是否登录。
 *
 * 早期版本曾把「图片上传 / 自动上传 / 后台补传 / 多设备同步」暴露给用户，
 * 结果是同一件事被拆成四个相互依赖的开关，默认值还互相冲突。旧安装残留的
 * 偏好值**不再读取**，升级后无需用户重新打开任何一项。
 *
 * 存储连接参数与密钥不在这里、也不在本机任何地方——它们只存在服务端
 * （Cloudflare Worker），由 [ImageCloudConfig] 依据登录态判断可用性。
 */
@Singleton
class ImageCloudPreferences @Inject constructor() {

    /** 登录即视为开启；未登录时所有云路径本就不会动作。 */
    private val alwaysOn = MutableStateFlow(true)

    /** 图片上传（含保存后自动上传）。 */
    val enabled: StateFlow<Boolean> = alwaysOn

    /** 保存物品后自动上传该物品的图片。 */
    val autoUpload: StateFlow<Boolean> = alwaysOn

    /** 是否允许后台定期补传（任务本身仅在不计费网络下运行）。 */
    val backgroundSync: StateFlow<Boolean> = alwaysOn

    /** 多设备文字数据同步。 */
    val cloudSyncEnabled: StateFlow<Boolean> = alwaysOn
}
