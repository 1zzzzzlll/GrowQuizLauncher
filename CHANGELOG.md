# Changelog

Plume shows the section for its own version in **Plume settings → Updates** after an update.
After an update, a card on the home screen shows the first 6 entries. Put the 6 most interesting changes first. Put small fixes last, as plain lines.
The build stops if `versionName` has no section here.

Write every entry in ASD-STE100 Simplified Technical English:

- Write one idea in each sentence. Use 20 words or fewer in each sentence.
- Use the active voice and the present tense.
- Write instructions as commands: "Select **Updates**", not "**Updates** can be selected".
- Use one word for one thing. Use the names that Plume shows on the screen.
- Start each entry with "- ". Plume shows each entry as one item. It removes `**` marks.

Put changes that are not released under `## Unreleased`. Rename that heading to the version number when you release.

## 1.8.0

- **答题前必须思考。** 出题后数秒内选项无效。屏幕显示剩余秒数。默认 5 秒，控制台可调 0–60 秒。
- **连续答错强制休息。** 连错 3 题休息 10 分钟。之后每多错 1 题，多休息 10 分钟。
- **蒙题会被验证。** 快速连错后又快速答对，系统追加一道验证题。验证失败按连错 3 题处理。
- **答题期间每秒检查前台。** 若答题或休息被切到后台，自动弹回。防止用其他应用跳过。
- **密码框闪退修复。** 管理密码改为系统圆点显示。输入密码时不再崩溃。
- **弹窗适配小屏。** 更新记录等弹窗内容过高时可滚动。按钮保持在屏幕内。
- **分类抽题更准确。** 所选分类在当前年龄无题时，不再悄悄改用全部分类。控制台按年龄显示分类题量；无题的分类不可勾选。
- **休息页显示管理地址。** 强制休息页面显示控制台地址和二维码。家长扫码即可解除休息。
- **管理后台可解除休息。** 控制台显示休息状态，可一键解除。也可关闭强制休息功能。
- **开机答题触发更可靠。** 开机后弹出管理端提醒。按「确定」立即答一题。
- **手机扫码输入密码。** 密码框新增二维码；手机扫码后在手机上输入密码，电视自动解锁，小孩看不到。
- **管理端地址可扫码直达。** 启动提醒弹窗新增二维码，手机扫码直接打开管理控制台。

## 1.7.1

- **密码输入改为星号。** 设置页输入管理密码时显示 *，键盘预览也不再泄露内容。
- **键盘“完成”直接确认。** 输完密码按输入法的完成键即可确认。

## 1.7.0

- **管理后台可关闭语音朗读。** 控制台新增「语音朗读题目」开关；关闭后答题只显示文字。
- **QQ 群二维码修复。** 二维码改由应用直接绘制，内容与群卡片一致，扫描更稳。
- **密码框按钮更清楚。** 确认按钮在深色背景下清晰可见，取消按钮改为中文。
- **新增遥控器专用键说明。** 说明拦截 Netflix 等一键直达键的方法。

## 1.6.1

- **启动提示自适应屏幕。** 提示框按屏幕分辨率调整大小，内容过长时可滚动，小屏电视也能看全。
- **管理密码框重做。** 密码输入框改为与应用一致的外观；遥控器可直接操作，输错时提示更清楚。
- **关于页新增 QQ 群。** 加入用户交流 QQ 群的按钮与群二维码；电视上让手机扫码即可加群。
- **GitHub 链接更明确。** 关于页的项目链接改为 GitHub 仓库，并配 GitHub 图标。

## 1.6.0

- **正式签名。** 应用改用正式密钥签名。此后所有新版本都可直接覆盖安装。
- **换版须知。** 从旧版本换到本版本时，必须先卸载旧版本。卸载会清除学习进度和设置。
- **GitHub 正式发布。** 新版本发布到 GitHub Releases，更新服务器定时同步。

## 1.5.0

- **题库扩展到 14 岁。** 年龄段从 5–10 岁扩展到 5–14 岁。新增 11–12 岁和 13–14 岁两个学段。
- **题量提升到 8000。** 每个年龄 800 题，是原来的两倍多。
- **新增四个学科。** 新增英语、历史、地理和初中理科题。语文题也大幅扩充。
- **数学难度升级。** 高年龄段加入方程、负数、几何、概率等题型。

## 1.4.1

- **方形图标。** 桌面图标改为铺满的方形。四角用徽章的蓝色填满，不再露出黑边。

## 1.4.0

- **应用改名。** 应用显示名改为“宝贝成长桌面”。设置标题、无障碍服务名和控制台页头一并修改。
- **全新图标。** 桌面图标、Android TV 横幅和控制台页头换成新的圆形徽章。
- **可直接升级。** 包名不变，旧版本可直接覆盖安装，布局和设置保留。

## 1.3.0

- **题库分类与多选。** 题库按年龄和分类两个维度整理，答题可按分类多选抽题，不选即为全部。
- **家长自定义出题。** 管理控制台新增自定义出题，题目需选择年龄和分类，与内置题一起随机出现。
- **题库维护。** 管理控制台可按年龄、分类、状态筛选题目，勾选后批量禁用或启用，也可一键开启或禁用全部题目。
- **答对自动退休。** 同一道题答对满 5 次后自动禁用，避免已掌握的题目反复出现。
- **设置密码保护。** 设置管理密码后，电视上的 Wi-Fi、系统设置和桌面设置入口也需输入密码。
- **修改管理密码。** 控制端支持修改管理密码，修改前需先验证原密码。
- **关于页面。** 设置菜单新增“关于”：查看当前版本与内部版本号、扫描二维码打开项目主页，并支持手动检查更新。
- **自动更新增强。** 更新来源服务器上的最新发版每 3 小时自动从项目仓库同步。

## 1.2.0

- **自动更新。** 每天自动检查一次新版本，发现后自动下载并安装；更新来源改为自有服务器，不再指向上游 Plume。

## 1.0.0

- **Grow Quiz launcher.** Add a Chinese children quiz to the open-source Plume TV home screen.
- **Offline bank.** Include 300 questions for each age from 5 through 10.
- **Device-owner mode.** Lock the full-screen quiz until the child selects a correct answer.
- **Parent settings.** Choose a child age, a quiz interval and the next default home screen.

## 1.7.1

- **Photo of the day fix.** Plume no longer closes when you select **Photo of the day** before the photo downloads.

## 1.7

- **A new Plume settings.** Clear sections with new icons. Select **Plume settings → Style → Theme → Wallpaper**, and Plume takes its colours from your wallpaper.
- **Clear glass.** See more of your wallpaper through tiles and menus. Select **Plume settings → Style → Glass → Clear**. The edges catch the light when you select a tile.
- **Aerial Views.** Apple, NASA and Amazon scenes play as your wallpaper or your screensaver. Select **Plume settings → Wallpaper → Live wallpapers**. You can also upload your own MP4.
- **Bigger apps.** Select **Plume settings → Apps row → Size**, then **Large** or **Largest**. Banners and icon tiles both grow.
- **New clock.** Date and weather show on one line, with a weather icon. Select **Plume settings → Clock & weather → Clock style** or **Clock size**.
- **New 4K wallpapers.** Select **Plume settings → Wallpaper → Built-in → More photos** to download ten 4K photos. New photos appear without an app update.
- **Aerial Views screensaver.** Select **Plume settings → Screensaver → Screensaver → Aerial Views**. It uses the scenes that you downloaded for the wallpaper, so each scene downloads only once.
- **Shows wallpaper.** The pictures of the shows in your apps become your wallpaper. Select **Plume settings → Wallpaper → Shows from** to choose the apps. Plume fetches nothing until you select it.
- **Edit the top bar.** Hold **OK** on an icon at the top of the home screen. Select **Move**, **Hide** or **Add icon**. **Plume settings** stays on the bar.
- **Power menu icon.** Hold **OK** on a top-bar icon and select **Add icon**, then **Power menu**. The icon opens the Android power menu.
- **More apps.** Plume shows installed apps that do not have a TV launcher entry. Some apps need touch or a mouse.
- **Italian.** Plume is now available in Italian.
- **The selected tile rises.** The selected tile or card moves up out of its row, so you can find it from across the room.
- **Wallpapers show as they are.** Plume no longer adds dark fades over a picture or a video. The text and the glass change to suit the picture.
- **Home returns to your app.** Press Home in an app that you opened from a tile or a card. That tile or card is selected.
- **Open from tile.** An app opens out of the tile that you press, and the row settles when you come back. Select **Plume settings → Style → Open from tile** to turn it off.
- **Remote keys.** Plume can tell apart two buttons that send the same key. To separate them, remove the old key and learn the button again.
- The top icons, the text and the progress bars change colour to match the wallpaper.
- The Glass setting changes tiles, cards and the settings panel without a restart.
- A tile is selected when Home first opens.
- A video wallpaper shows its own first frame while it starts.
- With **Large** or **Largest** apps, the apps row no longer covers the Continue watching title.
- Glass rows no longer show a grey edge after the last tile.
- Continue watching shows the show you watched last first in apps such as Nuvio.
- On Fire TV, Plume comes back when you press Home on the Plume home screen or in the Plume settings.
- On Google TV, a notice or a sign-in screen no longer brings Plume to the front.
- On some CVTE TVs, the Home button returns to Plume from HDMI or Live TV.
- On Xiaomi TVs, **Plume settings → Updates → Install** opens the TV's installer. Before, Plume closed.
- On debloated Google TVs, the **Finish setup** card does not come back after a restart. Folder wallpapers load after a restart.
- Plume no longer closes when the theme changes while a picture loads, or when the screensaver ends.
- Old wallpapers no longer ship in the app. An old selection shows a plain colour until you select another wallpaper.
- Plume is now available in Brazilian Portuguese.
- Continue watching no longer shows a season or an episode that is not a number.

## 1.6

### Design
- **Glass.** A frosted-glass blur behind the tiles and menus. Plume blurs the wallpaper behind the tiles, menus, cards and the settings panel. Glass is on after the update. To turn it off, select **Plume settings → Appearance → Glass**.
- **A new Plume settings.** Each section has an icon and a live preview. Under its name, each section shows its settings. While you change a setting, the part of the home screen it changes stays bright.
- **Banner icons.** Each app shows its own picture, as on Apple TV. An app with no banner gets its own colour behind a white mark. Select **Plume settings → Apps row → Icon style**.
- **Hide the Shows row.** Show only your apps and wallpaper, and press Down for your shows. Press Up to hide the row again. Select **Plume settings → Shows row → Show row**.
- **Auto follows the sun.** With **Theme** set to **Auto**, Plume changes to dark at sunset and to light at sunrise.
- **Text that fits the wallpaper.** The clock, the weather and the Continue watching text change to white or dark to match your photo.
- **Set how strong the fades are.** Select **Plume settings → Appearance → Top fade** or **Bottom fade**. Choose from 0% to 100%.
- **Colour icons on the Shows row.** Each card can show the app's logo in its own colour. Select **Plume settings → Shows row → Icons**.
- **Monochrome banner.** A fourth icon style. The pictures are grey until you select one.

### New
- **Sources.** A new icon in the top bar lists your TV inputs. A device such as a PS5 shows with its logo.
- **Remote keys.** Choose what a button on your remote does when you press it or hold it. Select **Plume settings → Remote & Home button → Remote keys**.
- **Uninstall an app.** Hold **OK** on an app in the row and select **Uninstall**.

### Easier to use
- **Setup fits your TV.** The Home button setup starts with the step that works on your TV.
- **Settings in home screen order.** The sections follow the home screen from top to bottom. Your settings do not change.
- A long app name scrolls slowly while the app is selected. It no longer runs past its tile.
- Plume shows in the app list on Xiaomi TVs.

### Fixes
- **Less memory.** Plume no longer runs twice after an update. It also keeps each picture only once.
- On a Fire TV, an HDMI tile switches to its input. Before, it opened Live TV.

## 1.5

### New
- **Pick your bottom row.** Show any channel from your apps, such as a Stremio library. Select **Plume settings → Bottom row → Channels**. The row takes the channel's name, or a name you set in **Row name**.
- **Bing wallpaper.** Use the Bing picture of the day as your wallpaper. Select **Plume settings → Appearance → Light mode wallpaper** or **Dark mode wallpaper**, then select **Bing picture of the day**.
- **New languages.** Plume is now available in English, French, Turkish and Arabic. It uses the language of your TV.
- **PIN for Wi-Fi and Android settings.** If you set a PIN, Plume asks for it before it opens Wi-Fi or Android settings from the top bar.

### Design
- **See-through tiles.** The wallpaper shows through the tiles. Menus and dialogs stay solid.
- **Smooth gradients.** The dark tiles and the wallpaper fade no longer show bands.
- **Smoother scrolling.** The app row moves more smoothly.

### Easier to use
- **Continue Watching order.** What you are watching comes before the next-episode suggestions. Plume also reads the time correctly from apps that give it in seconds.
- **App names.** When you move quickly along the app row, Plume shows an app's name only when you reach that app.

### Fixes
- Plume opens on Android 8.0 and 8.1. Before, it closed immediately after it opened.
- If your wallpaper is too big for the memory of your TV, Plume shows the default wallpaper. Before, Plume closed.
- In **All apps**, the tile in the first or last column is no longer cut off when you select it.

## 1.4

### Smaller
- **12% smaller.** Plume is now 365 KB, down from 413 KB.

### New
- **Consoles on your home screen.** Your PS5, Xbox or Nintendo Switch gets a tile with its logo. Select it to switch to that input.
- **More Continue Watching.** The limit of 3 shows for each app is gone. Each app keeps 3 places at the front.
- **Every HDMI input as a tile.** Other HDMI devices get a tile while they are on. All of them are in **All apps**.
- **What's new card.** After an update, a card shows the highlights. Select **See all changes** to read everything.

### Design
- **New dark mode.** True black, with lit edges on the selected item.
- **Plume settings, organised.** Settings are in sections. Each section shows its current settings.
- **App row position.** Put a short app row on the left, in the centre or on the right. Select **Plume settings → App row → App row position**.

### Easier to use
- **Faster settings.** Push Left or Right to change a value. Settings opens where you left it. At the top of a list, push Up to go to the bottom.
- **Weather location.** Set it by hand in **Plume settings → Weather → Weather location**.
- **Home button setup.** A guided setup makes the Home button open Plume. Select **Plume settings → Home button → Set up Home button**. **Home button** is now an on/off switch.
- **Update dot.** A red dot on the Plume settings icon tells you about an update. **Plume settings → Updates** shows what is new.

### Fixes
- After the TV starts, the first app is selected, not the network icon.
- The hold OK menu is only as wide as its items.

## 1.3

- You can use a folder of photos as your wallpaper. The folder can be on the TV or on a USB drive. Select **Plume settings → Folder photo changes** to set how often the photo changes.
- **Photo fade** removes the dark area at the bottom of your photos.
- You can use your photos as the screensaver. Select **Plume settings → Screensaver → Your photos**.
- **Lock layout** keeps the home screen safe from changes. Set a 4-digit PIN in **Plume settings → Lock layout**. Apps still open without the PIN.
- Hold OK on an app to show **Move**, **Remove from row** and **App info**.
- Hold OK on a Continue Watching card to show **Remove** and **Open app**.
- Continue Watching shows a maximum of three shows from each app.
- **Launcher settings** is now **Plume settings**. Back goes up one page.
- **Remove a photo…** deletes wallpapers that you uploaded.
- The Home button switch opens the correct settings screen on more TVs.

## 1.2.1

- Fix: with **Icon style → Colour**, some app icons stayed grey until Plume started again.

## 1.2

- Plume updates itself. It looks for a new version one time each day. Select **Plume settings → Updates** to install it.
- You can hide apps from the home row. Select **Plume settings → Apps in row**. Hidden apps stay in All apps.
- **App names** shows app names below the tiles: on focus, always or never.
- **Icon style → Colour** shows each logo in the colour of its brand.
- You can add wallpapers from your phone. Select **Add from phone…** in a wallpaper list, then scan the QR code.
- Fix: green areas no longer show in the sky of wallpapers.

## 1.1

- The APK is now 0.4 MB. It was 5.1 MB.
- The Gallery screensaver paintings are now a 4.7 MB download. Select **Plume settings → Screensaver → Gallery** to get them.
- Plume deletes the paintings when you select a different screensaver.
- Fix: Plume did not always find its Home button service.

## 1.0

- First release.
