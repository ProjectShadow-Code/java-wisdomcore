/*
 * Copyright (c) [2018]
 * This file is part of the java-wisdomcore
 *
 * The java-wisdomcore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The java-wisdomcore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the java-wisdomcore. If not, see <http://www.gnu.org/licenses/>.
 */

package org.wisdom.keystore.account;


import org.wisdom.keystore.crypto.KeyPair;
import org.wisdom.keystore.crypto.KeyPair;

public class Account {
    private  final KeyPair keyPair;
    private  final Address address;

    public Account(KeyPair keyPair,Address address){
        this.keyPair=keyPair;
        this.address=address;
    }


}