// :core:model: ids, glyph keys, platforms and other plain values every layer shares.
// Depends on nothing but the Compose stability annotation (annotation only, no Compose runtime).
plugins {
    id("xross.jvm.library")
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.runtime.annotation)
}
