package io.seqera.wave.core.spec

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/**
 * Model a container config specification
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@EqualsAndHashCode
@ToString(includePackage = false, includeNames = true)
@CompileStatic
class ConfigSpec {
    String hostName
    String domainName
    String user
    Boolean attachStdin
    Boolean attachStdout
    Boolean attachStderr
    Boolean tty
    List<String> env
    List<String> cmd
    String image
    String workingDir
    List<String> entrypoint

    ConfigSpec() {
        this(Map.of())
    }

    ConfigSpec(Map opts) {
        this.hostName = opts.Hostname
        this.domainName = opts.Domainname
        this.user = opts.User
        this.attachStdin = opts.AttachStdin as Boolean
        this.attachStdout = opts.AttachStdout as Boolean
        this.attachStderr = opts.AttachStderr as Boolean
        this.tty = opts.Tty as Boolean
        this.env = opts.Env as List<String> ?: List.<String>of()
        this.cmd = opts.Cmd as List<String> ?: List.<String>of()
        this.image = opts.Image
        this.workingDir = opts.WorkingDir
        this.entrypoint = opts.Entrypoint as List<String> ?: List.<String>of()
    }

}