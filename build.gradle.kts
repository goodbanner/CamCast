buildscript {
    dependencies {
        // AGP 9 built-in Kotlin defaults to KGP 2.2.10, which cannot read
        // kotlin-stdlib 2.3+ metadata pulled by RootEncoder 2.8.2.
        // Official override: https://developer.android.com/build/releases/agp-9-0-0-release-notes
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.3.2" apply false
}
