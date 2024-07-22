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

package io.seqera.wave.service.persistence.repository

import io.micronaut.data.annotation.Repository
import io.micronaut.data.mongodb.annotation.MongoRepository
import io.micronaut.data.repository.CrudRepository
import io.seqera.wave.service.persistence.WaveBuildRecord
/**
 * Interface for WaveBuildRecord repository
 *
 * @author : Munish Chouhan <munish.chouhan@seqera.io>
 */
@Repository
@MongoRepository
interface WaveBuildRepository extends CrudRepository<WaveBuildRecord, String>{
    Optional<WaveBuildRecord> findByTargetImageAndDigest(String targetImage, String digest)
}
