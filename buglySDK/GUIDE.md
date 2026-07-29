Bugly Android SDK 使用指南
接入前准备
库文件导入
自动集成（推荐）
集成SDK
手动集成（不推荐）
参数配置
最简单的初始化
合规问题处理
MultiDex注意事项
测试和Demo
接入前准备
在接入sdk之前，请务必认真阅读《开发者合规指南》和《Bugly SDK个人信息保护规则》

库文件导入
Bugly支持自动集成和手动集成两种方式，如果您使用Gradle编译Apk，我们强烈建议您使用自动接入方式配置库文件。

自动集成（推荐）
Bugly支持Maven Central仓库。为了便于版本管理，4.0.0以上版本已将jar和so合并发布。

集成SDK
在Module的build.gradle文件中添加依赖和属性配置：

android {
    defaultConfig {
        ndk {
            // 设置支持的SO库架构
            abiFilters 'armeabi' //, 'x86', 'armeabi-v7a', 'x86_64', 'arm64-v8a'
        }
    }
}

dependencies {
    implementation 'com.tencent.bugly:crashreport:latest.release' //其中latest.release指代最新Bugly SDK版本号，也可以指定明确的版本号，例如4.0.3
}
注意：4.0.0之前版本，还需要添加nativecrashreport依赖：

 implementation 'com.tencent.rqd:nativecrashreport:latest.release' //其中latest.release指代最新Bugly NDK版本号，也可以指定明确的版本号，例如3.9.2
注意：自动集成时会自动包含Bugly SO库，建议在Module的build.gradle文件中使用NDK的“abiFilter”配置，设置支持的SO库架构。

如果在添加“abiFilter”之后Android Studio出现以下提示：

NDK integration is deprecated in the current plugin.  Consider trying the new experimental plugin.

则在项目根目录的gradle.properties文件中添加：

android.useDeprecatedNdk=true

手动集成（不推荐）
如果您不采用上述自动导入方式，也可以手动集成Bugly SDK。

下载Bugly aar文件

下载Bugly的Android SDK包；
Android Studio工程

将aar文件复制到module的libs目录下，在module的gradle文件中添加依赖：
dependencies {
    implementation files('libs/Bugly_sdk.aar')
}
为了使APP Crash堆栈的可读性更高，建议您配置符号表文件，更准确地定位问题：

纯Java代码的工程：只需要配置混淆后生成的Mapping文件即可；
含有Native代码的工程：建议配置符号表工具从Debug SO中提取的Symbol符号表文件。
具体的配置方法请参考：《Bugly Android符号表配置》

参数配置
在AndroidManifest.xml中添加权限：
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
请避免混淆Bugly，在Proguard混淆文件中增加以下配置：
-dontwarn com.tencent.bugly.**
-keep public class com.tencent.bugly.**{*;}
最简单的初始化
请务必确保您已经将Bugly SDK升级到满足监管新规的最新版本
请务必在用户授权《隐私政策》后再初始化Bugly SDK

获取APP ID并调用初始化方法，Bugly会为自动检测环境并完成配置：

CrashReport.initCrashReport(getApplicationContext(), "注册时申请的APPID", false); 
为了保证运营数据的准确性，建议不要在异步线程初始化Bugly。
第三个参数为SDK调试模式开关，调试模式的行为特性如下：

输出详细的Bugly SDK的Log；
每一条Crash都会被立即上报；
自定义日志将会在Logcat中输出。
建议在测试阶段建议设置成true，发布时设置为false。

Alt text

此外，Bugly2.0及以上版本还支持通过“AndroidManifest.xml”来配置APP信息。如果同时又通过代码中配置了APP信息，则最终以代码配置的信息为准。

在“AndroidManifest.xml”的“Application”中增加“meta-data”配置项：

<application
    <!-- 配置APP ID -->
    <meta-data
            android:name="BUGLY_APPID"
            android:value="<APP_ID>" />
    <!-- 配置APP版本号 -->
    <meta-data
            android:name="BUGLY_APP_VERSION"
            android:value="<APP_Version>" />
    <!-- 配置APP渠道号 -->
    <meta-data
            android:name="BUGLY_APP_CHANNEL"
            android:value="<APP_Channel>" />
    <!-- 配置Bugly调试模式（true或者false）-->
    <meta-data
            android:name="BUGLY_ENABLE_DEBUG"
            android:value="<isDebug>" />
</application>
不同于“android:versionName”，“BUGLY_APP_VERSION”配置的是Bugly平台的APP版本号。

通过“AndroidManifest.xml”配置后的初始化方法如下：

CrashReport.initCrashReport(getApplicationContext());
Bugly默认从“AndroidManifest.xml”文件中读取“VersionName”作为版本号

Bugly提供了更多自定义功能设置，请参考“高级功能”。

合规问题处理
最新版SDK不会获取设备唯一标识，为了使得crash率统计更精准，建议业务设置自己的唯一id给SDK，详见高级功能-设置设备id
最新版SDK默认不会获取手机型号，如有需要请在合规获取到手机型号后设置给SDK，详见高级功能-设置设备型号
MultiDex注意事项
如果使用了MultiDex，建议通过Gradle的“multiDexKeepFile”配置等方式把Bugly的类放到主Dex，另外建议在Application类的"attachBaseContext"方法中主动加载非主dex：

public class MyApplication extends SomeOtherApplication {
  @Override
  protected void attachBaseContext(Context base) {
     super.attachBaseContext(context);
     Multidex.install(this);
  }
}
测试和Demo
现在您可以制造一个Crash（建议通过“按键”来触发），来体验Bugly的能力了。在初始化Bugly的之后，调用Bugly测Java Crash接口。

CrashReport.testJavaCrash();
执行到这段代码时会发生一个Crash，Logcat的TAG=CrashReportInfo中输出为：

Alt text

现在您已经可以在“崩溃”页面看到刚才触发的Crash issue了（延迟一般在10s以内）。

参考demo https://github.com/BuglyDevTeam/Bugly-Android-Demo

如果项目包含了Native工程或者使用了代码混淆，建议配置符号表文件，具体请参考“符号表配置”