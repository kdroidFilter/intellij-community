// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.ui.laf

import com.intellij.jna.JnaLoader
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.ui.mac.foundation.Foundation
import com.intellij.ui.mac.foundation.ID
import com.intellij.util.concurrency.NonUrgentExecutor
import com.sun.jna.Callback
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import org.jetbrains.annotations.NonNls
import java.awt.Toolkit
import java.beans.PropertyChangeEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.function.BiConsumer
import java.util.function.Consumer

sealed class SystemDarkThemeDetector {
  companion object {
    @JvmStatic
    fun createDetector(syncFunction: Consumer<Boolean>): SystemDarkThemeDetector =
      createParametrizedDetector { isDark, _ -> syncFunction.accept(isDark) }

    @JvmStatic
    fun createParametrizedDetector(syncFunction: BiConsumer<Boolean, Boolean?>): SystemDarkThemeDetector {
      return when {
        SystemInfoRt.isMac -> MacOSDetector(syncFunction)
        SystemInfo.isWin10OrNewer -> WindowsDetector(syncFunction)
        SystemInfo.isLinux -> LinuxDetector(syncFunction)
        else -> EmptyDetector()
      }
    }
  }

  fun check() { check(null) }
  abstract fun check(parameter: Boolean?)

  /**
   * The following method is executed on a polled thread. Maybe computationally intense.
   */
  abstract fun isDark(): Boolean

  abstract val detectionSupported: Boolean
}

private abstract class AsyncDetector : SystemDarkThemeDetector() {
  abstract val syncFunction: BiConsumer<Boolean, Boolean?>

  override fun check(parameter: Boolean?) {
    NonUrgentExecutor.getInstance().execute {
      val isDark = isDark()
      ApplicationManager.getApplication().invokeLater(Runnable { syncFunction.accept(isDark, parameter) }, ModalityState.any())
    }
  }
}

private class MacOSDetector(override val syncFunction: BiConsumer<Boolean, Boolean?>) : AsyncDetector() {
  override val detectionSupported: Boolean
    get() = SystemInfoRt.isMac && JnaLoader.isLoaded()

  val themeChangedCallback = object : Callback {
    @Suppress("unused")
    fun callback() { // self: ID, selector: Pointer, id: ID
      check(null)
    }
  }

  init {
    val pool = Foundation.NSAutoreleasePool()
    try {
      val selector = if (useAppearanceApi()) Foundation.createSelector("observeValueForKeyPath:ofObject:change:context:")
      else Foundation.createSelector("handleAppleThemeChanged:")

      val delegateClass = Foundation.allocateObjcClassPair(Foundation.getObjcClass("NSObject"), "NSColorChangesObserver")

      if (ID.NIL != delegateClass) {
          if (!Foundation.addMethod(delegateClass, selector, themeChangedCallback, "v@")) {
            throw RuntimeException("Cannot add observer method")
          }
          Foundation.registerObjcClassPair(delegateClass)
        }

      val delegate = Foundation.invoke("NSColorChangesObserver", "new")

      if (useAppearanceApi()) {
        val app = Foundation.invoke("NSApplication", "sharedApplication")
        Foundation.invoke(app, "addObserver:forKeyPath:options:context:", delegate, Foundation.nsString("effectiveAppearance"),
                          0x01 /*NSKeyValueObservingOptionNew*/, ID.NIL)
      }
      else {
        Foundation.invoke(Foundation.invoke("NSDistributedNotificationCenter", "defaultCenter"), "addObserver:selector:name:object:",
                          delegate,
                          selector,
                          Foundation.nsString("AppleInterfaceThemeChangedNotification"),
                          ID.NIL)
      }
    }
    finally {
      pool.drain()
    }
  }

  override fun isDark(): Boolean {
    val pool = Foundation.NSAutoreleasePool()
    try {
      if (useAppearanceApi()) {
        val app = Foundation.invoke("NSApplication", "sharedApplication")
        val name = Foundation.toStringViaUTF8(Foundation.invoke(Foundation.invoke(app, "effectiveAppearance"), "name"))
        return name?.equals("NSAppearanceNameDarkAqua") ?: false
      }

      // https://developer.apple.com/forums/thread/118974
      val userDefaults = Foundation.invoke("NSUserDefaults", "standardUserDefaults")
      val appleInterfaceStyle = Foundation.toStringViaUTF8(Foundation.invoke(userDefaults, "objectForKey:", Foundation.nsString("AppleInterfaceStyle")))

      return appleInterfaceStyle?.lowercase(Locale.getDefault())?.contains("dark") ?: false
    }
    finally{
      pool.drain()
    }
  }

  private fun useAppearanceApi() = SystemInfo.isMacOSCatalina && "system".equals(System.getProperty("apple.awt.application.appearance"), true)
}

private class WindowsDetector(override val syncFunction: BiConsumer<Boolean, Boolean?>) : AsyncDetector() {
  override val detectionSupported: Boolean
    get() = SystemInfo.isWin10OrNewer && JnaLoader.isLoaded()

  companion object {
    @NonNls const val REGISTRY_PATH = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
    @NonNls const val REGISTRY_VALUE = "AppsUseLightTheme"
  }

  init {
    Toolkit.getDefaultToolkit().addPropertyChangeListener("win.lightTheme.on") { e: PropertyChangeEvent ->
      ApplicationManager.getApplication().invokeLater(Runnable { syncFunction.accept(e.newValue != java.lang.Boolean.TRUE, null) }, ModalityState.any())
    }
  }

  override fun isDark(): Boolean {
    try {
      return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, REGISTRY_PATH, REGISTRY_VALUE) &&
             Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, REGISTRY_PATH, REGISTRY_VALUE) == 0
    }
    catch (e: Throwable) {}
    return false
  }
}
/**
* Inspired by the LinuxThemeDetector implementation from the Platform-Tools repository by kdroidFilter.
* Repository: https://github.com/kdroidFilter/Platform-Tools
**/
private class LinuxDetector(override val syncFunction: BiConsumer<Boolean, Boolean?>) : AsyncDetector() {
  override val detectionSupported: Boolean
    get() = SystemInfo.isLinux

  companion object {
    // Commands for theme detection
    @NonNls private const val MONITORING_CMD = "gsettings monitor org.gnome.desktop.interface"
    @NonNls private val GET_CMD = arrayOf(
      "gsettings get org.gnome.desktop.interface gtk-theme",
      "gsettings get org.gnome.desktop.interface color-scheme"
    )
    private val darkThemeRegex = ".*dark.*".toRegex(RegexOption.IGNORE_CASE)

    @Volatile
    private var detectorThread: Thread? = null
  }

  init {
    startMonitoring()
  }

  override fun isDark(): Boolean {
    return try {
      val runtime = Runtime.getRuntime()
      for (cmd in GET_CMD) {
        val process = runtime.exec(cmd)
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
          val line = reader.readLine()
          if (line != null && isDarkTheme(line)) {
            return true
          }
        }
      }
      false
    } catch (e: Exception) {
      false
    }
  }

  private fun startMonitoring() {
    if (detectorThread?.isAlive == true) return

    detectorThread = object : Thread("GTK Theme Detector Thread") {
      private var lastValue: Boolean = isDark()

      override fun run() {
        val runtime = Runtime.getRuntime()
        val process = try {
          runtime.exec(MONITORING_CMD)
        } catch (e: Exception) {
          return
        }

        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
          while (!isInterrupted) {
            val line = reader.readLine() ?: break
            if (!line.contains("gtk-theme", ignoreCase = true) &&
                !line.contains("color-scheme", ignoreCase = true)
            ) {
              continue
            }

            val currentIsDark = isDarkThemeFromLine(line) ?: isDark()

            if (currentIsDark != lastValue) {
              lastValue = currentIsDark
              ApplicationManager.getApplication().invokeLater(
                { syncFunction.accept(currentIsDark, null) },
                ModalityState.any()
              )
            }
          }
          if (process.isAlive) {
            process.destroy()
          }
        }
      }
    }.apply {
      isDaemon = true
      start()
    }
  }

  private fun isDarkThemeFromLine(line: String): Boolean? {
    val tokens = line.split("\\s+".toRegex())
    if (tokens.size < 2) {
      return null
    }
    val value = tokens[1].lowercase().replace("'", "")
    return if (value.isNotBlank()) {
      isDarkTheme(value)
    } else {
      null
    }
  }

  private fun isDarkTheme(text: String): Boolean {
    return darkThemeRegex.matches(text)
  }
}

private class EmptyDetector : SystemDarkThemeDetector() {
  override val detectionSupported = false
  override fun isDark() = false
  override fun check(parameter: Boolean?) {}
}