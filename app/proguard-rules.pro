# 현재 release 빌드는 minify를 끈 상태다. 켤 경우 아래 규칙부터 시작한다.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keep class com.c2c.app.stt.WhisperLib { *; }
