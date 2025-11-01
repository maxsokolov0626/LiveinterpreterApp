buildscript {
    repositories {
        google()
        mavenCentral()
    }
}
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // for Vosk
    }
}
