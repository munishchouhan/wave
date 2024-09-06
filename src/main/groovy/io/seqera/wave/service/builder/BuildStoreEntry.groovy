/*
 *  Wave, containers provisioning service
 *  Copyright (c) 2024, Seqera Labs
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.seqera.wave.service.builder

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/**
 * Class to store build request and result in cache
 *
 * @author Munish Chouhan <munish.chouhan@seqera.io>
 */
@ToString(includePackage = false, includeNames = true)
@EqualsAndHashCode
@CompileStatic
class BuildStoreEntry {

    final BuildRequest request

    final BuildResult result

    protected BuildStoreEntry() {}

    BuildStoreEntry(BuildRequest request, BuildResult result) {
        this.request = request
        this.result = result
    }

    BuildStoreEntry withResult(BuildResult result) {
        new BuildStoreEntry(request, result)
    }
}
