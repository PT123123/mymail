# JavaMail 的 javax/jakarta 注解与 AAPT 冲突时按需放开;当前未开混淆
-keep class com.sun.mail.** { *; }
-keep class jakarta.mail.** { *; }
-keep class com.wmail.core.** { *; }
