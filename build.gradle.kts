plugins {
    // SESL9 requires API 37 and ships Java 24 bytecode. AGP 9.3 supports both.
    // Kotlin support is built into AGP 9.
    id("com.android.application") version "9.3.3" apply false
}
