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

package io.seqera.wave.store.range
/**
 * Define the contract for a storage range set similar to Redis `zrange`
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
interface RangeStore {

    /**
     * Add an entry to the range with the specified score
     *
     * @param name
     *      The name of the entry to be added
     * @param score
     *      The score of the entry as {@code double} value
     */
    void add(String entry, double score)

    /**
     * Get a list of entries having a score within the specified range
     *
     * @param min
     *      The range lower bound
     * @param max
     *      The range upper bound
     * @param count
     *      The max number of entries that can be returned
     * @return
     *      The list of entries matching the specified range or an empty list if no entry matches the range specified
     */
    List<String> getRange(double min, double max, int count)
}
