/*
 *  Wave, containers provisioning service
 *  Copyright (c) 2023-2024, Seqera Labs
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

package io.seqera.wave.service.persistence.impl

import groovy.transform.CompileStatic
import io.seqera.wave.core.ContainerDigestPair
import io.seqera.wave.service.persistence.CondaPackageRecord
import io.seqera.wave.service.persistence.PersistenceService
import io.seqera.wave.service.persistence.WaveBuildRecord
import io.seqera.wave.service.persistence.WaveContainerRecord
import io.seqera.wave.service.persistence.WaveScanRecord
import jakarta.inject.Singleton
/**
 * Basic persistence for dev purpose
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Singleton
@CompileStatic
class LocalPersistenceService implements PersistenceService {

    private Map<String,WaveBuildRecord> buildStore = new HashMap<>()

    private Map<String,WaveContainerRecord> requestStore = new HashMap<>()
    private Map<String,WaveScanRecord> scanStore = new HashMap<>()
    private Map<String, CondaPackageRecord> condaStore = new HashMap<>()

    @Override
    void saveBuild(WaveBuildRecord record) {
        buildStore[record.buildId] = record
    }

    @Override
    WaveBuildRecord loadBuild(String buildId) {
        return buildStore.get(buildId)
    }

    @Override
    void saveContainerRequest(String token, WaveContainerRecord data) {
        requestStore.put(token, data)
    }

    @Override
    void updateContainerRequest(String token, ContainerDigestPair digest) {
        final data = requestStore.get(token)
        if( data ) {
            requestStore.put(token, new WaveContainerRecord(data, digest.source, digest.target))
        }
    }

    @Override
    WaveContainerRecord loadContainerRequest(String token) {
        requestStore.get(token)
    }

    @Override
    void createScanRecord(WaveScanRecord scanRecord) {
        scanStore.put(scanRecord.id, scanRecord)
    }

    @Override
    void updateScanRecord(WaveScanRecord scanRecord) {
        scanStore.put(scanRecord.id, scanRecord)
    }

    @Override
    WaveScanRecord loadScanRecord(String scanId) {
        scanStore.get(scanId)
    }

    @Override
    void saveCondaPackages(List<CondaPackageRecord> entries) {
        for( CondaPackageRecord entry : entries )
            condaStore.put(entry.id, entry)
    }

    @Override
    List<CondaPackageRecord> findCondaPackage(String criteria, List<String> channels) {
        if( !criteria && !channels )
            return condaStore.values() as List<CondaPackageRecord>

        List<CondaPackageRecord> result = new ArrayList<>()

        if (!channels) {
            for (CondaPackageRecord it : condaStore.values()) {
                if (it.id.contains(criteria))
                    result.add(it)
            }
            return result
        }

        if( !criteria ) {
            for (CondaPackageRecord it : condaStore.values()) {
                if (channels.contains(it.channel))
                    result.add(it)
            }
            return result
        }

        for( CondaPackageRecord it : condaStore.values() ) {
            if( it.id.contains(criteria) && channels.contains(it.channel))
                result.add(it)
        }

        return result
    }
}
