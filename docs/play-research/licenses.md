# Android 运行时依赖许可清单（自动生成）

- 生成日期：2026-11-10
- 生成方式：`cd android && ./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath`，再用 `docs/play-research/gen_licenses.py` 读取 Gradle 缓存里每个依赖的 POM（沿 `<parent>` 查找）。
- 依赖树去重后的依赖数：104；表格行数：104。

## 按许可汇总

| 许可 | 数量 |
|---|---|
| Apache-2.0 | 104 |

## 完整清单

| group | artifact | 版本 | 直接依赖 | 许可 | 来源 |
|---|---|---|---|---|---|
| androidx.activity | activity | 1.10.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.activity | activity-compose | 1.10.0 | 是 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.activity | activity-ktx | 1.10.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.annotation | annotation | 1.9.1 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.annotation | annotation-experimental | 1.4.1 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.annotation | annotation-jvm | 1.9.1 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.arch.core | core-common | 2.2.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.arch.core | core-runtime | 2.2.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.autofill | autofill | 1.0.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.collection | collection | 1.5.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.collection | collection-jvm | 1.5.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.collection | collection-ktx | 1.5.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.animation | animation | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.animation | animation-android | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.animation | animation-core | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.animation | animation-core-android | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.foundation | foundation | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.foundation | foundation-android | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.foundation | foundation-layout | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.foundation | foundation-layout-android | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.material3 | material3 | 1.3.2 | 是 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.material3 | material3-android | 1.3.2 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.material | material-icons-core | 1.7.8 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.material | material-icons-core-android | 1.7.8 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.material | material-icons-extended | 1.7.8 | 是 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.material | material-icons-extended-android | 1.7.8 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.material | material-ripple | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.material | material-ripple-android | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.runtime | runtime | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.runtime | runtime-android | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.runtime | runtime-saveable | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.runtime | runtime-saveable-android | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.ui | ui | 1.8.3 | 是 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.ui | ui-android | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.ui | ui-geometry | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.ui | ui-geometry-android | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.ui | ui-graphics | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.ui | ui-graphics-android | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.ui | ui-text | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.ui | ui-text-android | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.ui | ui-tooling-preview | 1.8.3 | 是 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.ui | ui-tooling-preview-android | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.ui | ui-unit | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.ui | ui-unit-android | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.ui | ui-util | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose.ui | ui-util-android | 1.8.3 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.compose | compose-bom | 2025.07.00 | 是 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.concurrent | concurrent-futures | 1.1.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.concurrent | concurrent-futures-ktx | 1.1.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.core | core | 1.15.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.core | core-ktx | 1.15.0 | 是 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.customview | customview-poolingcontainer | 1.0.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.documentfile | documentfile | 1.0.1 | 是 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.emoji2 | emoji2 | 1.4.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.graphics | graphics-path | 1.0.1 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.interpolator | interpolator | 1.0.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-common | 2.8.7 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-common-java8 | 2.8.7 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-common-jvm | 2.8.7 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-livedata | 2.8.7 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-livedata-core | 2.8.7 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-livedata-core-ktx | 2.8.7 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-process | 2.8.7 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-runtime | 2.8.7 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-runtime-android | 2.8.7 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-runtime-compose | 2.8.7 | 是 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-runtime-compose-android | 2.8.7 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-runtime-ktx | 2.8.7 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-runtime-ktx-android | 2.8.7 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-service | 2.8.7 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-viewmodel | 2.8.7 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-viewmodel-android | 2.8.7 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-viewmodel-compose | 2.8.7 | 是 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-viewmodel-compose-android | 2.8.7 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-viewmodel-ktx | 2.8.7 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.lifecycle | lifecycle-viewmodel-savedstate | 2.8.7 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.profileinstaller | profileinstaller | 1.4.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.room | room-common | 2.6.1 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.room | room-ktx | 2.6.1 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.room | room-runtime | 2.6.1 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.savedstate | savedstate | 1.2.1 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.savedstate | savedstate-ktx | 1.2.1 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.sqlite | sqlite | 2.4.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.sqlite | sqlite-framework | 2.4.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.startup | startup-runtime | 1.1.1 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.tracing | tracing | 1.2.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.tracing | tracing-ktx | 1.2.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.versionedparcelable | versionedparcelable | 1.1.1 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.work | work-runtime | 2.10.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| androidx.work | work-runtime-ktx | 2.10.0 | 是 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| com.google.guava | listenablefuture | 1.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 父 POM（1 级） |
| com.squareup.okhttp3 | okhttp | 4.12.0 | 是 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| com.squareup.okio | okio | 3.6.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| com.squareup.okio | okio-jvm | 3.6.0 |  | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| org.jetbrains.kotlin | kotlin-stdlib | 2.0.21 | 是 | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| org.jetbrains.kotlin | kotlin-stdlib-common | 2.0.21 |  | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| org.jetbrains.kotlin | kotlin-stdlib-jdk7 | 1.9.10 |  | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| org.jetbrains.kotlin | kotlin-stdlib-jdk8 | 1.9.10 |  | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| org.jetbrains.kotlinx | kotlinx-coroutines-android | 1.7.3 |  | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| org.jetbrains.kotlinx | kotlinx-coroutines-bom | 1.7.3 |  | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| org.jetbrains.kotlinx | kotlinx-coroutines-core | 1.7.3 |  | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| org.jetbrains.kotlinx | kotlinx-coroutines-core-jvm | 1.7.3 |  | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| org.jetbrains | annotations | 23.0.0 |  | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
| org.jspecify | jspecify | 1.0.0 |  | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 自身 POM |
