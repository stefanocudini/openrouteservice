/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package heigit.ors.partitioning;


import com.graphhopper.routing.weighting.AbstractWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.DataAccess;
import com.graphhopper.storage.Directory;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.Storable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stores eccentricities of cell border nodes for fast isochrones. eccentricities are weighting dependent, therefore they are stored separately
 *
 * @author Hendrik Leuschner
 */
public class EccentricityStorage implements Storable<EccentricityStorage> {

    private final DataAccess eccentricities;
    private int ECCENTRICITYBYTES;
    private int FULLYREACHABLEPOSITION;
    private int ECCENTRICITYPOSITION;
    private int nodeCount;
    private Weighting weighting;

    public EccentricityStorage(GraphHopperStorage graph, Directory dir, Weighting weighting) {

        final String name = AbstractWeighting.weightingToFileName(weighting);
        eccentricities = dir.find("eccentricities_" + name);
        this.weighting = weighting;
        //TODO for now just create for all nodes... optimize to use only border nodes... will save 95% space
        nodeCount = graph.getNodes();
        //  1 int per eccentricity value
        this.ECCENTRICITYBYTES = 8;
        this.FULLYREACHABLEPOSITION = 0;
        this.ECCENTRICITYPOSITION = FULLYREACHABLEPOSITION + 4;

    }


    @Override
    public boolean loadExisting() {
        if (eccentricities.loadExisting()) {
            return true;
        }
        return false;
    }

    public void init() {
        eccentricities.create(1000);
        eccentricities.ensureCapacity(nodeCount * ECCENTRICITYBYTES);
    }


    public void setEccentricity(int node, double eccentricity){
        eccentricities.setInt(node * ECCENTRICITYBYTES + ECCENTRICITYPOSITION, (int)Math.ceil(eccentricity));
    }

    public int getEccentricity(int node){
        return eccentricities.getInt(node * ECCENTRICITYBYTES + ECCENTRICITYPOSITION);
    }

    public void setFullyReachable(int node, boolean isFullyReachable){
        if(isFullyReachable)
//            eccentricities.setInt(node * ECCENTRICITYBYTES + FULLYREACHABLEPOSITION, new byte[] {(byte) 1}, 1);
        eccentricities.setInt(node * ECCENTRICITYBYTES + FULLYREACHABLEPOSITION, 1);
        else
            eccentricities.setInt(node * ECCENTRICITYBYTES + FULLYREACHABLEPOSITION, 0);
    }

    public boolean getFullyReachable(int node){
//        byte[] buffer = new byte[1];
        int isFullyReachable = eccentricities.getInt(node * ECCENTRICITYBYTES + FULLYREACHABLEPOSITION);

        return isFullyReachable == 1;
    }

    @Override
    public EccentricityStorage create(long byteCount) {
        throw new IllegalStateException("Do not call EccentricityStorage.create directly");
    }

    @Override
    public void flush() {
        eccentricities.flush();
    }

    @Override
    public void close() {
        eccentricities.close();

    }

    @Override
    public boolean isClosed() {
        return eccentricities.isClosed();
    }

    public long getCapacity() {
        return eccentricities.getCapacity();
    }

    public Weighting getWeighting() {
        return weighting;
    }
}