package com.lsd.wififrankenstein.util

import com.topjohnwu.superuser.Shell
import java.util.regex.Pattern

class ChrootDiagnostics(
    private val busyboxPath: String,
    private val chrootPath: String = "/data/local/wififrankenstein/chroot"
) {

    companion object {
        private const val TAG = "ChrootDiagnostics"

        private val AVC_REGEX = Pattern.compile(
            """avc:\s+denied\s+\{\s*([^}]+)\s*\}.*?scontext=([^\s]+).*?tcontext=([^\s]+).*?tclass=([^\s]+)""",
            Pattern.CASE_INSENSITIVE
        )
        private val COMM_REGEX = Pattern.compile("""comm="([^"]+)"""")
    }

    data class StageResult(
        val name: String,
        val description: String,
        val success: Boolean,
        val exitCode: Int,
        val output: String,
        val avcEntries: List<AvcEntry>,
        val suggestedRules: List<String>
    )

    data class MountInfo(
        val device: String,
        val mountPoint: String,
        val fstype: String,
        val options: List<String>
    ) {
        val isNoexec: Boolean get() = options.contains("noexec")
        val isExec: Boolean get() = !isNoexec || options.contains("exec")
        val isRealFs: Boolean
            get() = fstype !in setOf(
                "proc", "sysfs", "tmpfs", "devpts", "none",
                "overlay", "squashfs", "selinuxfs", "pstore",
                "functionfs", "binder", "cg2_bpf", "cg1_bpf"
            )
    }

    fun runDiagnostic(onStageResult: ((StageResult) -> Unit)? = null): List<StageResult> {
        val results = mutableListOf<StageResult>()
        val tmp = "/data/local/tmp/.wififrank_diag_${System.currentTimeMillis()}"

        val stages = listOf(
            Stage(
                "context",
                "SELinux context",
                "id -Z 2>/dev/null; cat /proc/self/attr/current 2>/dev/null"
            ),
            Stage("root", "Root access", "id | grep -q 'uid=0'"),
            Stage("busybox", "Busybox binary", "$busyboxPath true"),
            Stage("busybox_execute", "Execute from app_data_file", "$busyboxPath echo BUSYBOX_OK"),
            Stage("selinux_status", "SELinux mode", "getenforce"),
            Stage(
                "chroot_syscall", "chroot() syscall",
                "mkdir -p $tmp/chroot/bin && cp $busyboxPath $tmp/chroot/bin/busybox && " +
                        "$busyboxPath chroot $tmp/chroot /bin/busybox true",
                cleanup = "rm -rf $tmp/chroot"
            ),
            Stage(
                "chroot_execute", "Execute inside chroot",
                "mkdir -p $tmp/chroot2/bin && cp $busyboxPath $tmp/chroot2/bin/sh && " +
                        "$busyboxPath chroot $tmp/chroot2 /bin/sh -c 'echo EXEC_OK'",
                cleanup = "rm -rf $tmp/chroot2"
            ),
            Stage(
                "bind_mount", "Bind mount",
                "mkdir -p $tmp/mnt1 $tmp/mnt2 && $busyboxPath mount --bind $tmp/mnt1 $tmp/mnt2",
                cleanup = "$busyboxPath umount $tmp/mnt2 2>/dev/null; rm -rf $tmp/mnt1 $tmp/mnt2"
            ),
            Stage(
                "mount_dev", "Mount --bind /dev",
                "mkdir -p $tmp/dev && $busyboxPath mount --bind /dev $tmp/dev",
                cleanup = "$busyboxPath umount $tmp/dev 2>/dev/null; rm -rf $tmp/dev"
            ),
            Stage(
                "mount_devpts", "Mount devpts",
                "mkdir -p $tmp/devpts && $busyboxPath mount -t devpts devpts $tmp/devpts",
                cleanup = "$busyboxPath umount $tmp/devpts 2>/dev/null; rm -rf $tmp/devpts"
            ),
            Stage(
                "mount_proc", "Mount procfs",
                "mkdir -p $tmp/proc && $busyboxPath mount -t proc proc $tmp/proc",
                cleanup = "$busyboxPath umount $tmp/proc 2>/dev/null; rm -rf $tmp/proc"
            ),
            Stage(
                "mount_tmpfs", "Mount tmpfs",
                "mkdir -p $tmp/tmp && $busyboxPath mount -t tmpfs tmpfs $tmp/tmp",
                cleanup = "$busyboxPath umount $tmp/tmp 2>/dev/null; rm -rf $tmp/tmp"
            ),
            Stage(
                "mount_sysfs", "Mount sysfs",
                "mkdir -p $tmp/sys && $busyboxPath mount -t sysfs sysfs $tmp/sys",
                cleanup = "$busyboxPath umount $tmp/sys 2>/dev/null; rm -rf $tmp/sys"
            ),
            Stage(
                "unshare", "unshare -m",
                "$busyboxPath unshare -m true"
            ),
            Stage(
                "mknod", "Device node",
                "mkdir -p $tmp/dev && mknod $tmp/dev/null c 1 3",
                cleanup = "rm -rf $tmp/dev"
            ),
            Stage(
                "symlink", "Symlink /proc/self/fd",
                "mkdir -p $tmp/link && ln -sf /proc/self/fd $tmp/link && " +
                        "$busyboxPath ls -la $tmp/link/fd/0",
                cleanup = "rm -rf $tmp/link"
            ),
            Stage(
                "tun_device", "TUN device",
                "mkdir -p $tmp/net && mknod $tmp/net/tun c 10 200",
                cleanup = "rm -rf $tmp/net"
            ),
            Stage(
                "bind_exec_mount", "bind,exec mount",
                "mkdir -p $tmp/be1 $tmp/be2 && " +
                        "$busyboxPath mount -o bind,exec $tmp/be1 $tmp/be2",
                cleanup = "$busyboxPath umount $tmp/be2 2>/dev/null; rm -rf $tmp/be1 $tmp/be2"
            ),
            Stage(
                "magiskpolicy", "magiskpolicy binary",
                "command -v magiskpolicy 2>/dev/null || which magiskpolicy 2>/dev/null"
            ),
            Stage(
                "kernel_version", "Kernel version",
                "$busyboxPath uname -r 2>/dev/null || cat /proc/version 2>/dev/null || echo unknown"
            ),
            Stage(
                "proc_filesystems", "Supported filesystems",
                "cat /proc/filesystems 2>/dev/null"
            ),
            Stage(
                "proc_mounts_noexec", "noexec mounts",
                "cat /proc/mounts 2>/dev/null"
            ),
            Stage(
                "capabilities", "Process capabilities",
                "cat /proc/self/status 2>/dev/null | grep -E '^Cap(Bnd|Eff|Inh|Prm)'"
            ),
            Stage(
                "exec_directories", "Exec directories",
                "cat /proc/mounts 2>/dev/null"
            ),
            Stage(
                "exec_candidate_paths", "Exec test on candidates",
                "echo CANDIDATES_BEGIN; " +
                        "for d in /data/local/tmp /data/adb /data/local /cache /data; do " +
                        "echo \"--- \$d ---\"; " +
                        "if [ -d \"\$d\" ]; then " +
                        "tmpf=\"\$d/.exec_test_\\$\\$\"; " +
                        "echo '#!/system/bin/sh' > \"\$tmpf\" 2>/dev/null && " +
                        "chmod 755 \"\$tmpf\" 2>/dev/null && " +
                        "\"\$tmpf\" 2>&1 && echo \"EXEC_OK\" || echo \"EXEC_FAIL\"; " +
                        "rm -f \"\$tmpf\" 2>/dev/null; " +
                        "else echo \"DIR_MISSING\"; fi; " +
                        "done; echo CANDIDATES_END"
            ),
            Stage(
                "busybox_linkage", "Busybox linkage type",
                "$busyboxPath sh -c 'if $busyboxPath ldd $busyboxPath 2>&1 | grep -q \"not a dynamic\"; then echo STATIC; else echo DYNAMIC; fi'"
            ),
            Stage(
                "linker_chroot", "Chroot with linker",
                "mkdir -p $tmp/linker_test/bin $tmp/linker_test/system/bin $tmp/linker_test/system/lib && " +
                        "cp $busyboxPath $tmp/linker_test/bin/busybox && " +
                        "cp /system/bin/linker $tmp/linker_test/system/bin/ 2>/dev/null; " +
                        "cp /system/lib/libc.so $tmp/linker_test/system/lib/ 2>/dev/null; " +
                        "$busyboxPath chroot $tmp/linker_test /bin/busybox true 2>&1",
                cleanup = "rm -rf $tmp/linker_test"
            ),
            Stage(
                "system_chroot", "System /system/bin/chroot",
                "echo SYS_CHROOT_BEGIN; " +
                        "if [ -x /system/bin/chroot ]; then " +
                        "mkdir -p $tmp/syschroot/bin && " +
                        "cp $busyboxPath $tmp/syschroot/bin/busybox && " +
                        "/system/bin/chroot $tmp/syschroot /bin/busybox true 2>&1 && " +
                        "echo SYS_CHROOT_OK || echo SYS_CHROOT_FAIL; " +
                        "else echo SYS_CHROOT_NOT_FOUND; fi",
                cleanup = "rm -rf $tmp/syschroot"
            ),
            Stage(
                "kernel_chroot_config", "Kernel CONFIG_CHROOT",
                "if [ -f /proc/config.gz ]; then " +
                        "$busyboxPath zcat /proc/config.gz 2>/dev/null | grep CONFIG_CHROOT; " +
                        "elif [ -f /boot/config ]; then " +
                        "grep CONFIG_CHROOT /boot/config 2>/dev/null; " +
                        "else echo CONFIG_UNKNOWN; fi"
            ),
            Stage(
                "seccomp_status", "Seccomp filter",
                "grep '^Seccomp:' /proc/self/status 2>/dev/null || echo 'Seccomp: N/A'"
            ),
            Stage(
                "knox_indicators", "Samsung Knox",
                "getprop ro.boot.knox 2>/dev/null; getprop ro.config.knox 2>/dev/null; " +
                        "[ -d /data/knox ] && echo KNOX_DATA; " +
                        "[ -f /system/bin/knox_chroot ] && echo KNOX_BIN"
            ),
            Stage(
                "chroot_sysctl", "Kernel chroot_enabled",
                "cat /proc/sys/kernel/chroot_enabled 2>/dev/null || echo N/A"
            ),
            Stage(
                "proot_available", "proot alternative",
                "command -v proot 2>/dev/null || echo NO_PROOT"
            )
        )

        for (stage in stages) {
            Log.i(TAG, "=== Stage: ${stage.name} ===")

            val linesBefore = getDmesgLineCount()
            val shellResult = Shell.cmd(stage.testCmd).exec()
            Thread.sleep(250)
            val newAvc = collectNewAvc(linesBefore)

            stage.cleanup?.let { cleanup ->
                Shell.cmd(cleanup).exec()
            }

            val output = (shellResult.out + shellResult.err).joinToString("\n")
            val rules = newAvc.mapNotNull { it.toMagiskRule() }.distinct()

            val isSuccess = when (stage.name) {
                "selinux_status", "kernel_version", "proc_filesystems",
                "proc_mounts_noexec", "exec_directories", "context",
                "capabilities", "mount_sysfs", "busybox_linkage",
                "kernel_chroot_config", "seccomp_status", "knox_indicators",
                "chroot_sysctl" -> true

                "magiskpolicy" -> shellResult.isSuccess && output.isNotBlank()
                "system_chroot" -> output.contains("SYS_CHROOT_OK")
                "proot_available" -> output.isNotBlank()
                else -> shellResult.isSuccess && shellResult.code == 0 && newAvc.isEmpty()
            }

            val result = StageResult(
                name = stage.name,
                description = stage.description,
                success = isSuccess,
                exitCode = shellResult.code,
                output = output,
                avcEntries = newAvc,
                suggestedRules = rules
            )

            results.add(result)
            onStageResult?.invoke(result)
            Log.d(TAG, "Stage ${stage.name}: success=$isSuccess, avc=${newAvc.size}")
        }

        Shell.cmd("rm -rf $tmp 2>/dev/null").exec()
        return results
    }

    fun testChrootInstalled(): StageResult {
        val linesBefore = getDmesgLineCount()
        val cmd = "$busyboxPath chroot $chrootPath /bin/busybox sh -c 'echo CHROOT_ALIVE'"
        val shellResult = Shell.cmd(cmd).exec()
        Thread.sleep(250)
        val newAvc = collectNewAvc(linesBefore)
        val output = (shellResult.out + shellResult.err).joinToString("\n")
        val rules = newAvc.mapNotNull { it.toMagiskRule() }.distinct()
        val success = shellResult.code == 0 && shellResult.out.any { it.trim() == "CHROOT_ALIVE" }
        return StageResult(
            name = "chroot_alive",
            description = "Chroot alive test",
            success = success,
            exitCode = shellResult.code,
            output = output,
            avcEntries = newAvc,
            suggestedRules = rules
        )
    }

    fun collectAvcFromLastDmesg(): List<AvcEntry> {
        return collectNewAvc(0)
    }

    fun buildUserReport(results: List<StageResult>): String = buildString {
        appendLine("Chroot Diagnostics")
        appendLine("─".repeat(50))

        val failed = results.filter { !it.success }
        val allAvc = results.flatMap { it.avcEntries }
        val allRules = results.flatMap { it.suggestedRules }.distinct()
        val selinuxStage = results.find { it.name == "selinux_status" }
        val contextStage = results.find { it.name == "context" }
        val kernelStage = results.find { it.name == "kernel_version" }

        if (contextStage != null) {
            val ctx = contextStage.output.lineSequence().firstOrNull()?.trim() ?: "unknown"
            appendLine("  Context: $ctx")
        }
        if (selinuxStage != null) {
            appendLine("  SELinux: ${selinuxStage.output.trim()}")
        }
        if (kernelStage != null) {
            appendLine("  Kernel: ${kernelStage.output.trim()}")
        }
        appendLine()

        for (r in results) {
            val icon = if (r.success) "[OK]" else "[FAIL]"
            appendLine("$icon ${r.description}")

            if (!r.success) {
                appendLine("  exit=${r.exitCode}")
                r.output.lineSequence().firstOrNull()?.take(120)?.let {
                    appendLine("  $it")
                }
            }
            if (r.avcEntries.isNotEmpty()) {
                appendLine("  AVC:")
                r.avcEntries.forEach { appendLine("    ${it.toReadable()}") }
            }
        }

        if (allAvc.isNotEmpty()) {
            appendLine()
            appendLine("─ AVC Rules (${allRules.size}) ─")
            allRules.forEach { appendLine("  $it") }
        }

        if (failed.isNotEmpty() && allRules.isNotEmpty()) {
            appendLine()
            appendLine("─ Apply ─")
            appendLine("  magiskpolicy --live \"${allRules.first()}\"")
            val magiskStage = results.find { it.name == "magiskpolicy" }
            if (magiskStage?.success == true) {
                appendLine("  (magiskpolicy available — auto-apply supported)")
            } else {
                appendLine("  (magiskpolicy not found — create sepolicy.rule module manually)")
            }
        }
    }

    fun applyMagiskRules(results: List<StageResult>): Boolean {
        val rules = results.flatMap { it.suggestedRules }.distinct()
        if (rules.isEmpty()) return true

        val check =
            Shell.cmd("command -v magiskpolicy 2>/dev/null || which magiskpolicy 2>/dev/null")
                .exec()
        if (check.out.isEmpty()) {
            Log.e(TAG, "magiskpolicy not found in PATH")
            return false
        }

        var ok = true
        for (rule in rules) {
            val cmd = """magiskpolicy --live "$rule" 2>&1"""
            val res = Shell.cmd(cmd).exec()
            if (res.isSuccess) {
                Log.i(TAG, "Applied: $rule")
            } else {
                Log.w(TAG, "Failed: $rule, err=${res.err.joinToString()}")
                ok = false
            }
        }
        return ok
    }

    fun buildSepolicyModuleContent(results: List<StageResult>): String = buildString {
        val rules = results.flatMap { it.suggestedRules }.distinct()
        appendLine("# Magisk module SELinux rules")
        appendLine("# Save as /data/adb/modules/<your_module>/sepolicy.rule")
        appendLine("# Reboot required after creating")
        appendLine()
        rules.forEach { appendLine(it) }
    }

    fun findExecDirectories(results: List<StageResult>): List<Pair<String, Boolean>> {
        val mountsStage =
            results.find { it.name == "exec_directories" || it.name == "proc_mounts_noexec" }
                ?: return emptyList()
        val mountExec = parseMounts(mountsStage.output)
            .filter { m -> m.isRealFs && m.mountPoint.startsWith("/data/") }
            .map { m -> m.mountPoint to (m.isExec || !m.isNoexec) }
        val candidates = parseExecCandidates(results)
        return (mountExec + candidates).distinctBy { it.first }
    }

    fun findExecCandidates(results: List<StageResult>): List<Pair<String, Boolean>> {
        return parseExecCandidates(results)
    }

    private fun parseExecCandidates(results: List<StageResult>): List<Pair<String, Boolean>> {
        val stage = results.find { it.name == "exec_candidate_paths" } ?: return emptyList()
        val result = mutableListOf<Pair<String, Boolean>>()
        var currentDir: String? = null
        for (line in stage.output.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("--- ") && trimmed.endsWith(" ---") -> {
                    currentDir = trimmed.removePrefix("--- ").removeSuffix(" ---")
                }

                trimmed == "EXEC_OK" && currentDir != null -> {
                    result.add(currentDir to true)
                }

                (trimmed == "EXEC_FAIL" || trimmed == "DIR_MISSING") && currentDir != null -> {
                    result.add(currentDir to false)
                }
            }
        }
        return result
    }

    fun findSELinuxProblemType(results: List<StageResult>): String? {
        val busyboxExecAvc = results
            .find { it.name == "busybox_execute" }
            ?.avcEntries
            ?.filter {
                it.tclass == "file" && (it.permissions.contains("execute") || it.permissions.contains(
                    "execute_no_trans"
                ))
            }
            .orEmpty()

        val chrootExecAvc = results
            .find { it.name == "chroot_execute" }
            ?.avcEntries
            ?.filter {
                it.tclass == "file" && (it.permissions.contains("execute") || it.permissions.contains(
                    "execute_no_trans"
                ))
            }
            .orEmpty()

        return when {
            busyboxExecAvc.any { it.tcontext.contains("app_data_file") } &&
                    chrootExecAvc.isEmpty() -> {
                val candidates = findExecCandidates(results)
                val execDirs = candidates.filter { it.second }.map { it.first }
                if (execDirs.isNotEmpty()) {
                    "MCS: busybox in app_data_file — move to ${execDirs.first()}/"
                } else {
                    "MCS: busybox in app_data_file — move to /data/adb/ or /data/local/"
                }
            }

            chrootExecAvc.any { it.permissions.contains("execute_no_trans") } ->
                "domain_transition: need execute_no_trans for shell_data_file"

            else -> null
        }
    }

    private fun parseMounts(output: String): List<MountInfo> {
        return output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split("\\s+".toRegex())
                if (parts.size < 4) return@mapNotNull null
                MountInfo(
                    device = parts[0],
                    mountPoint = parts[1],
                    fstype = parts[2],
                    options = parts[3].split(",")
                )
            }
            .toList()
    }

    private data class Stage(
        val name: String,
        val description: String,
        val testCmd: String,
        val cleanup: String? = null
    )

    private fun getDmesgLineCount(): Int {
        return try {
            val r = Shell.cmd("dmesg 2>/dev/null | $busyboxPath wc -l").exec()
            r.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun collectNewAvc(linesBefore: Int): List<AvcEntry> {
        val fromDmesg = try {
            val cmd = "dmesg 2>/dev/null | $busyboxPath tail -n +${linesBefore + 1}"
            val r = Shell.cmd(cmd).exec()
            r.out.mapNotNull { parseAvcLine(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read dmesg", e)
            emptyList()
        }
        if (fromDmesg.isNotEmpty()) return fromDmesg

        return try {
            val cmd =
                "logcat -b events -d 2>/dev/null | $busyboxPath grep -i 'avc:.*denied' | $busyboxPath tail -20"
            Shell.cmd(cmd).exec().out.mapNotNull { parseAvcLine(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read logcat events", e)
            emptyList()
        }
    }

    private fun parseAvcLine(line: String): AvcEntry? {
        if (!line.contains("avc:", ignoreCase = true)) return null
        if (!line.contains("denied", ignoreCase = true)) return null

        val m = AVC_REGEX.matcher(line)
        if (!m.find()) return null

        val perms = m.group(1)?.trim() ?: return null
        val sctx = m.group(2) ?: return null
        val tctx = m.group(3) ?: return null
        val tcls = m.group(4) ?: return null

        val commM = COMM_REGEX.matcher(line)
        val comm = if (commM.find()) commM.group(1) else null

        return AvcEntry(sctx, tctx, tcls, perms, line, comm)
    }
}
