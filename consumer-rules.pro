# Consumer rules for monitor-proxy
# libXray（gomobile 生成）的 Java 层通过 JNI 与 Go 交互，不能被混淆或裁剪
-keep class go.** { *; }
-keep class libXray.** { *; }
