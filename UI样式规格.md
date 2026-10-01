# souti (com.jxw.souti) UI 样式规格

来源：学习机预装 APK `jxwhy_souti_ht.apk` v20241203.16（2024-12-03 构建）反编译 + 真机运行时的 uiautomator 层级 dump。真机像素截图被 FLAG_SECURE 拦截，全黑不可用。

## 设备形态
- 横屏 960×400（词典笔/学习机小屏），锁定横向（SENSOR_LANDSCAPE）
- 尺寸体系：`x1 ≈ 1.41px` 网格（x24≈34px、x28≈40px、x30≈42px），布局全部按 x/y 单位适配

## 色彩
| 用途 | 值 |
|---|---|
| 全局背景 | `#03183A` 深蓝 |
| 卡片背景 | `#1C2F4F` |
| 半透明胶囊/按钮 | `#33FFFFFF`（白 20%） |
| 输入框底 | `#26FFFFFF`（白 15%） |
| 主行动色（蓝） | `#3C92E8` |
| 强调/次行动（琥珀） | `#FFBF00` |
| 正文 | `#FFFFFF`；次要文字 `#80FFFFFF`、`#898989`、`#ADADAD` |
| 分隔线 | `icon_fgx` 蓝色渐变细线 |

## 页面清单（res/layout，共 13 个业务页）
- activity_main：智能答疑主页（扫描引导插图 + 两条提示 + 右下「拍照答疑」圆钮 + 顶部「错题本」胶囊）；内含无网络页、编辑资料页、家长管控引导页、扫描中页、未识别页五个内嵌态
- activity_photo_souti：拍照答疑（左侧相机取景 + 四角对焦框，右侧栏：错题本/快门/扫描搜题）
- activity_wrong_book：错题本列表（标题居中「全部▾」筛选、右上「管理」；空态插图 icon_zwsc +「你还没有收藏题目哦」）
- adapter_wrong_book：题目卡（#1C2F4F 圆角卡，底部：题号灰 / 时间灰居中 / 学科蓝右对齐）
- activity_wrong_book_details：详情（「题干 / 解答」双 tab + 「打印」蓝按钮）
- activity_subject_selection / activity_tag_selection：科目弹层（全部+语数英物化生政史地科社 11 科，chip 网格 + 取消/确认）
- activity_report_errors：报错弹层（题干信息有误/解析内容有误 + 立即报错）
- activity_print_set / activity_print_preview / activity_center / activity_bind / activity_distinguish_result

## 组件风格
- 按钮：全圆角胶囊（radius≈20 单位），蓝底或白 20% 半透明；图标+文字横排
- 弹层：深蓝底、细白描边、居中标题、双胶囊按钮收尾
- 图标：白色单色 mipmap（back/tjiao/gl/sc/xl/km/pz/pzst/wc/cp…）

## 运行依赖（为何模拟器里看不到真实内容）
- 登录态来自 `com.jxw.launcher/com.jht.engine.platsign.LoginService`（学习机桌面绑定服务），模拟器无此服务
- 请求加密依赖 32 位 `libopenapi.so`（armeabi-v7a only），Apple Silicon 模拟器无 32 位 ARM 支持
- 已做处理：剥 v7a 库 + smali 补丁容错 native 加载 → 应用可在模拟器启动不崩溃，但拿不到数据
- 产物：`apk/souti_swap_final.apk`（已装 emulator-5554）；复刻页 `replica/index.html`（http://127.0.0.1:8090 与模拟器 Chrome 10.0.2.2:8090 双端可开）
