/*
 *  Copyright (c) 2023, Seqera Labs.
 *
 *  This Source Code Form is subject to the terms of the Mozilla Public
 *  License, v. 2.0. If a copy of the MPL was not distributed with this
 *  file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 *  This Source Code Form is "Incompatible With Secondary Licenses", as
 *  defined by the Mozilla Public License, v. 2.0.
 */

package io.seqera.wave.service.mail.impl


import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Value
import io.micronaut.runtime.event.annotation.EventListener
import io.seqera.mail.Mail
import io.seqera.mail.MailAttachment
import io.seqera.mail.MailHelper
import io.seqera.mail.MailerConfig
import io.seqera.wave.service.builder.BuildEvent
import io.seqera.wave.service.builder.BuildRequest
import io.seqera.wave.service.builder.BuildResult
import io.seqera.wave.service.mail.MailService
import io.seqera.wave.service.mail.MailSpooler
import jakarta.inject.Inject
import jakarta.inject.Singleton
import static io.seqera.wave.util.DataTimeUtils.formatDuration
import static io.seqera.wave.util.DataTimeUtils.formatTimestamp

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@Requires(env = 'mail')
@Singleton
@CompileStatic
class MailServiceImpl implements MailService {

    @Inject
    private MailSpooler spooler

    @Inject
    private MailerConfig config

    @Value('${wave.server.url}')
    private String serverUrl

    @EventListener
    void onBuildEvent(BuildEvent event) {
        try {
            sendCompletionEmail(event.request, event.result)
        }
        catch (Exception e) {
            log.warn "Unable to send completion notication - reason: ${e.message?:e}"
        }
    }

    @Override
    void sendCompletionEmail(BuildRequest request, BuildResult build) {
        // send to user email address or fallback to the system `mail.from` address
        final user = request.user
        final recipient = user ? user.email : config.from
        if( recipient ) {
            final result = build ?: BuildResult.unknown()
            final mail = buildCompletionMail(request, result, recipient)
            spooler.sendMail(mail)
        }
        else {
            log.debug "Missing email recipient from build id=$build.id - user=$user"
        }
    }

    Mail buildCompletionMail(BuildRequest req, BuildResult result, String recipient) {
        // create template binding
        final binding = new HashMap(20)
        final status = result.succeeded() ? 'DONE': 'FAILED'
        binding.build_id = result.id
        binding.build_user =  "${req.user ? req.user.userName : 'n/a'} (${req.ip})"
        binding.build_success = result.exitStatus==0
        binding.build_exit_status = result.exitStatus
        binding.build_time = formatTimestamp(result.startTime, req.offsetId) ?: '-'
        binding.build_duration = formatDuration(result.duration) ?: '-'
        binding.build_image = req.targetImage
        binding.build_format = req.format?.render() ?: 'Docker'
        binding.build_platform = req.platform
        binding.build_containerfile = req.containerFile ?: '-'
        binding.build_condafile = req.condaFile
        binding.build_spackfile = req.spackFile
        binding.put('build_logs', result.logs)
        binding.build_url = "$serverUrl/view/builds/${result.id}"
        binding.scan_url = req.scanId && result.succeeded() ? "$serverUrl/view/scans/${req.scanId}" : null
        binding.scan_id = req.scanId
        binding.put('server_url', serverUrl)
        // result the main object
        Mail mail = new Mail()
        mail.to(recipient)
        mail.subject("Wave container result completion - ${status}")
        mail.body(MailHelper.getTemplateFile('/io/seqera/wave/build-notification.html', binding))
        mail.attach(MailAttachment.resource('/io/seqera/wave/seqera-logo.png', contentId: '<seqera-logo>', disposition: 'inline'))
        return mail
    }

}
