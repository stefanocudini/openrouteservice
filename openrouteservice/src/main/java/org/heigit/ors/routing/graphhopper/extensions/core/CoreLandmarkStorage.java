/*  This file is part of Openrouteservice.
 *
 *  Openrouteservice is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version 2.1
 *  of the License, or (at your option) any later version.

 *  This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.

 *  You should have received a copy of the GNU Lesser General Public License along with this library;
 *  if not, see <https://www.gnu.org/licenses/>.
 */
package org.heigit.ors.routing.graphhopper.extensions.core;

import com.carrotsearch.hppc.IntArrayList;
import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.predicates.IntObjectPredicate;
import com.carrotsearch.hppc.procedures.IntObjectProcedure;
import com.graphhopper.coll.MapEntry;
import com.graphhopper.routing.DijkstraBidirectionRef;
import com.graphhopper.routing.ch.PreparationWeighting;
import com.graphhopper.routing.lm.LandmarkStorage;
import com.graphhopper.routing.lm.LandmarkSuggestion;
import com.graphhopper.routing.subnetwork.SubnetworkStorage;
import com.graphhopper.routing.util.*;
import com.graphhopper.routing.util.spatialrules.SpatialRule;
import com.graphhopper.routing.util.spatialrules.SpatialRuleLookup;
import com.graphhopper.routing.weighting.AbstractWeighting;
import com.graphhopper.routing.weighting.ShortestWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.*;
import com.graphhopper.util.*;
import com.graphhopper.util.exceptions.ConnectionNotFoundException;
import com.graphhopper.util.shapes.BBox;
import com.graphhopper.util.shapes.GHPoint;
import org.heigit.ors.routing.graphhopper.extensions.edgefilters.EdgeFilterSequence;
import org.heigit.ors.routing.graphhopper.extensions.edgefilters.core.LMEdgeFilterSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Store Landmark distances for core nodes
 *
 * This code is based on that from GraphHopper GmbH.
 *
 * @author Peter Karich
 * @author Hendrik Leuschner
 */
public class CoreLandmarkStorage implements Storable<LandmarkStorage>{
    private static final Logger LOGGER = LoggerFactory.getLogger(CoreLandmarkStorage.class);
    // This value is used to identify nodes where no subnetwork is associated
    private static final int UNSET_SUBNETWORK = -1;
    // This value should only be used if subnetwork is too small to be explicitely stored
    private static final int UNCLEAR_SUBNETWORK = 0;
    // one node has an associated landmark information ('one landmark row'): the forward and backward weight
    private long lmRowLength;
    private int landmarks;
    private final int fromOffset;
    private final int toOffset;
    private final DataAccess landmarkWeightDA;
    /* every subnetwork has its own landmark mapping but the count of landmarks is always the same */
    private final List<int[]> landmarkIDs;
    private double factor = -1;
    private static final double DOUBLE_MLTPL = 1e6;
    private final GraphHopperStorage graph;
    private final CHGraphImpl core;
    private final FlagEncoder encoder;
    private final Weighting weighting;
    private Weighting lmSelectionWeighting;
    private Weighting lmWeighting;
    private final TraversalMode traversalMode;
    private boolean initialized;
    private int minimumNodes = 10000;
    private final SubnetworkStorage subnetworkStorage;
    private List<LandmarkSuggestion> landmarkSuggestions = Collections.emptyList();
    private SpatialRuleLookup ruleLookup;
    private boolean logDetails = false;
    private LMEdgeFilterSequence landmarksFilter;
    private int count = 0;

    private Map<Integer, Integer> coreNodeIdMap;
    /**
     * 'to' and 'from' fit into 32 bit => 16 bit for each of them => 65536
     */
    static final long PRECISION = 1 << 16;

    public CoreLandmarkStorage(Directory dir, GraphHopperStorage graph, Map<Integer, Integer> coreNodeIdMap, final Weighting weighting, LMEdgeFilterSequence landmarksFilter, int landmarks) {
        this.graph = graph;
        this.coreNodeIdMap = coreNodeIdMap;
        this.core = graph.getCoreGraph(weighting);
        this.minimumNodes = Math.min(core.getCoreNodes() / 2, 10000);
        this.encoder = weighting.getFlagEncoder();
        this.landmarksFilter = landmarksFilter;

        //Adapted from NodeContractor
        this.lmWeighting = new PreparationWeighting(weighting);
        
        this.weighting = weighting;
        // allowing arbitrary weighting is too dangerous
        this.lmSelectionWeighting = new ShortestWeighting(encoder) {
            @Override
            public double calcWeight(EdgeIteratorState edge, boolean reverse, int prevOrNextEdgeId) {
                // make accessibility of shortest identical to the provided weighting to avoid problems like shown in testWeightingConsistence
                CHEdgeIteratorState tmp = (CHEdgeIteratorState) edge;
                double res;
                if (tmp.isShortcut()) {
                    count = 0;
                    res = tmp.getWeight();

                    if (res >= Double.MAX_VALUE)
                        return Double.POSITIVE_INFINITY;

                    expandEdge(tmp, false);

                    return count;
                }
                else res = weighting.calcWeight(edge, reverse, prevOrNextEdgeId);
                if (res >= Double.MAX_VALUE)
                    return Double.POSITIVE_INFINITY;

                // returning the time or distance leads to strange landmark positions (ferries -> slow&very long) and BFS is more what we want
                return 1;
            }

            @Override
            public String toString() {
                return "LM_BFS|" + encoder;
            }
        };

        // Edge based is not really necessary because when adding turn costs while routing we can still
        // use the node based traversal as this is a smaller weight approximation and will still produce correct results
        this.traversalMode = TraversalMode.NODE_BASED;
        final String name = AbstractWeighting.weightingToFileName(weighting, traversalMode.isEdgeBased()) + landmarksFilter.getName();
        this.landmarkWeightDA = dir.find("landmarks_core_" + name);

        this.landmarks = landmarks;
        // one short per landmark and two directions => 2*2 byte
        this.lmRowLength = landmarks * 4L;
        this.fromOffset = 0;
        this.toOffset = 2;
        this.landmarkIDs = new ArrayList<>();
        this.subnetworkStorage = new SubnetworkStorage(dir, "landmarks_core_" + name);
    }

    private void expandEdge(CHEdgeIteratorState mainEdgeState, boolean reverse) {
        if (!mainEdgeState.isShortcut()) {
            count += 1;
            return;
        }

        int skippedEdge1 = mainEdgeState.getSkippedEdge1();
        int skippedEdge2 = mainEdgeState.getSkippedEdge2();
        int from = mainEdgeState.getBaseNode();
        int to = mainEdgeState.getAdjNode();


        CHEdgeIteratorState iter = core.getEdgeIteratorState(skippedEdge1, from);
            boolean empty = iter == null;
            if (empty)
                iter =  core.getEdgeIteratorState(skippedEdge2, from);

            expandEdge(iter, true);

            if (empty)
                iter =  core.getEdgeIteratorState(skippedEdge1, to);
            else
                iter =  core.getEdgeIteratorState(skippedEdge2, to);

            expandEdge(iter, false);

    }

    /**
     * This method calculates the landmarks and initial weightings to & from them.
     */
    public void createLandmarks() {
        if (isInitialized())
            throw new IllegalStateException("Initialize the landmark storage only once!");

        // fill 'from' and 'to' weights with maximum value
        long maxBytes = (long) core.getCoreNodes() * lmRowLength;
        this.landmarkWeightDA.create(2000);
        this.landmarkWeightDA.ensureCapacity(maxBytes);

        for (long pointer = 0; pointer < maxBytes; pointer += 2) {
            landmarkWeightDA.setShort(pointer, (short) SHORT_INFINITY);
        }

        String additionalInfo = "";
        // guess the factor
        if (factor <= 0) {
            // A 'factor' is necessary to store the weight in just a short value but without loosing too much precision.
            // This factor is rather delicate to pick, we estimate it through the graph boundaries its maximum distance.
            // For small areas we use max_bounds_dist*X and otherwise we use a big fixed value for this distance.
            // If we would pick the distance too big for small areas this could lead to (slightly) suboptimal routes as there
            // will be too big rounding errors. But picking it too small is dangerous regarding performance
            // e.g. for Germany at least 1500km is very important otherwise speed is at least twice as slow e.g. for just 1000km
            //TODO use core only maybe? Probably not that important because core.getBounds() ~= baseGraph.getBounds()

            BBox bounds = graph.getBounds();
            double distanceInMeter = Helper.DIST_EARTH.calcDist(bounds.maxLat, bounds.maxLon, bounds.minLat,
                    bounds.minLon) * 7;
            if (distanceInMeter > 50_000 * 7 || /* for tests and convenience we do for now: */ !bounds.isValid())
                distanceInMeter = 30_000_000;

            double maxWeight = weighting.getMinWeight(distanceInMeter);
            setMaximumWeight(maxWeight);
            additionalInfo = ", maxWeight:" + maxWeight + ", from max distance:" + distanceInMeter / 1000f + "km";
        }

        if (logDetails && LOGGER.isInfoEnabled())
            LOGGER.info(String.format("init landmarks for subnetworks with node count greater than %d with factor:%s%s", minimumNodes, factor, additionalInfo));

        int[] empty = new int[landmarks];
        Arrays.fill(empty, UNSET_SUBNETWORK);
        landmarkIDs.add(empty);

        byte[] subnetworks = new byte[core.getCoreNodes()];
        Arrays.fill(subnetworks, (byte) UNSET_SUBNETWORK);
        EdgeFilterSequence tarjanFilter = new EdgeFilterSequence();
        IntHashSet blockedEdges = new IntHashSet();

        // the ruleLookup splits certain areas from each other but avoids making this a permanent change so that other algorithms still can route through these regions.
        if (ruleLookup != null && ruleLookup.size() > 0) {
            StopWatch sw = new StopWatch().start();
            blockedEdges = findBorderEdgeIds(ruleLookup);
            if (logDetails&& LOGGER.isInfoEnabled())
                LOGGER.info(String.format("Made %d edges inaccessible. Calculated country cut in %ss, %s", blockedEdges.size(), sw.stop().getSeconds(), Helper.getMemInfo()));
        }

        tarjanFilter.add(new CoreAndBlockedEdgesFilter(encoder, false, true, blockedEdges));
        tarjanFilter.add(landmarksFilter);


        StopWatch sw = new StopWatch().start();

        // we cannot reuse the components calculated in PrepareRoutingSubnetworks as the edgeIds changed in between (called graph.optimize)
        // also calculating subnetworks from scratch makes bigger problems when working with many oneways
        TarjansCoreSCCAlgorithm tarjanAlgo = new TarjansCoreSCCAlgorithm(graph, core,  tarjanFilter, false);
        List<IntArrayList> graphComponents = tarjanAlgo.findComponents();
        if (logDetails && LOGGER.isInfoEnabled())
            LOGGER.info(String.format("Calculated %d subnetworks via tarjan in %ss, %s", graphComponents.size(), sw.stop().getSeconds(), Helper.getMemInfo()));
        CHEdgeExplorer tmpExplorer = this.core.createEdgeExplorer(new CoreAndRequireBothDirectionsEdgeFilter(encoder));

        int nodes = 0;
        for (IntArrayList subnetworkIds : graphComponents) {
            nodes += subnetworkIds.size();
            if (subnetworkIds.size() < minimumNodes)
                continue;

            int index = subnetworkIds.size() - 1;
            // ensure start node is reachable from both sides and no subnetwork is associated
            for (; index >= 0; index--) {
                int nextStartNode = subnetworkIds.get(index);
                if (subnetworks[coreNodeIdMap.get(nextStartNode)] == UNSET_SUBNETWORK
                    && GHUtility.count(tmpExplorer.setBaseNode(nextStartNode)) > 0
                    && createLandmarksForSubnetwork(nextStartNode, subnetworks, blockedEdges))
                    break;
            }
        }

        int subnetworkCount = landmarkIDs.size();
        // store all landmark node IDs and one int for the factor itself.
        this.landmarkWeightDA.ensureCapacity(
                maxBytes /* landmark weights */ + subnetworkCount * landmarks /* landmark mapping per subnetwork */);

        // calculate offset to point into landmark mapping
        long bytePos = maxBytes;
        for (int[] localLandmarks : landmarkIDs) {
            for (int lmNodeId : localLandmarks) {
                landmarkWeightDA.setInt(bytePos, lmNodeId);
                bytePos += 4L;
            }
        }
        //Changed to core
        landmarkWeightDA.setHeader(0, core.getCoreNodes());
        landmarkWeightDA.setHeader(4, landmarks);
        landmarkWeightDA.setHeader(8, subnetworkCount);
        if (factor * DOUBLE_MLTPL > Integer.MAX_VALUE)
            throw new UnsupportedOperationException( "landmark weight factor cannot be bigger than Integer.MAX_VALUE " + factor * DOUBLE_MLTPL);
        landmarkWeightDA.setHeader(12, (int) Math.round(factor * DOUBLE_MLTPL));

        // serialize fast byte[] into DataAccess
        //Changed to core
        subnetworkStorage.create(core.getCoreNodes());
        for (int nodeId = 0; nodeId < subnetworks.length; nodeId++) {
            subnetworkStorage.setSubnetwork(nodeId, subnetworks[nodeId]);
        }

        if (logDetails && LOGGER.isInfoEnabled())
            //Changed to core
            LOGGER.info(String.format("Finished landmark creation. Subnetwork node count sum %d vs. nodes %d", nodes, core.getCoreNodes()));
        initialized = true;
    }


    /**
     * This method creates landmarks for the specified subnetwork (integer list)
     *
     * @return landmark mapping
     */
    protected boolean createLandmarksForSubnetwork(final int startNode, final byte[] subnetworks, IntHashSet blockedEdges) {
        final int subnetworkId = landmarkIDs.size();
        int[] tmpLandmarkNodeIds = new int[landmarks];
        int logOffset = Math.max(1, tmpLandmarkNodeIds.length / 2);
        boolean pickedPrecalculatedLandmarks = false;

        if (!landmarkSuggestions.isEmpty()) {
            NodeAccess na = graph.getNodeAccess();
            double lat = na.getLatitude(startNode);
            double lon = na.getLongitude(startNode);
            LandmarkSuggestion selectedSuggestion = null;
            for (LandmarkSuggestion lmsugg : landmarkSuggestions) {
                if (lmsugg.getBox().contains(lat, lon)) {
                    selectedSuggestion = lmsugg;
                    break;
                }
            }

            if (selectedSuggestion != null) {
                if (selectedSuggestion.getNodeIds().size() < tmpLandmarkNodeIds.length)
                    throw new IllegalArgumentException("landmark suggestions are too few "
                            + selectedSuggestion.getNodeIds().size() + " for requested landmarks " + landmarks);

                pickedPrecalculatedLandmarks = true;
                for (int i = 0; i < tmpLandmarkNodeIds.length; i++) {
                    int lmNodeId = selectedSuggestion.getNodeIds().get(i);
                    tmpLandmarkNodeIds[i] = lmNodeId;
                }
            }
        }

        if (pickedPrecalculatedLandmarks && LOGGER.isInfoEnabled())
            LOGGER.info(String.format("Picked %d landmark suggestions, skipped expensive landmark determination", tmpLandmarkNodeIds.length));
        else {
            // 1a) pick landmarks via special weighting for a better geographical spreading
            Weighting initWeighting = lmSelectionWeighting;
            CoreLandmarkExplorer explorer = new CoreLandmarkExplorer(graph, this, initWeighting, traversalMode);
            explorer.initFrom(startNode, 0);
            EdgeFilterSequence coreEdgeFilter = new EdgeFilterSequence();
            coreEdgeFilter.add(new CoreAndBlockedEdgesFilter(encoder, true, true, blockedEdges));
            coreEdgeFilter.add(landmarksFilter);
            explorer.setFilter(coreEdgeFilter);
            explorer.runAlgo(true, coreEdgeFilter);

            if (explorer.getFromCount() < minimumNodes) {
                // too small subnetworks are initialized with special id==0
                explorer.setSubnetworks(subnetworks, UNCLEAR_SUBNETWORK);
                return false;
            }

            // 1b) we have one landmark, now determine the other landmarks
            tmpLandmarkNodeIds[0] = explorer.getLastNode();
            for (int lmIdx = 0; lmIdx < tmpLandmarkNodeIds.length - 1; lmIdx++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new RuntimeException("Thread was interrupted");
                }
                explorer = new CoreLandmarkExplorer(graph, this, initWeighting, traversalMode);
                explorer.setFilter(coreEdgeFilter);
                // set all current landmarks as start so that the next getLastNode is hopefully a "far away" node
                for (int j = 0; j < lmIdx + 1; j++) {
                    explorer.initFrom(tmpLandmarkNodeIds[j], 0);
                }
                explorer.runAlgo(true, coreEdgeFilter);
                tmpLandmarkNodeIds[lmIdx + 1] = explorer.getLastNode();
                if (logDetails && lmIdx % logOffset == 0)
                    LOGGER.info("Finding landmarks [" + weighting + "] in network [" + explorer.getVisitedNodes()
                            + "]. " + "Progress " + (int) (100.0 * lmIdx / tmpLandmarkNodeIds.length) + "%, "
                            + Helper.getMemInfo());
            }

            if (logDetails)
                LOGGER.info("Finished searching landmarks for subnetwork " + subnetworkId + " of size "
                        + explorer.getVisitedNodes());
        }

        // 2) calculate weights for all landmarks -> 'from' and 'to' weight
        for (int lmIdx = 0; lmIdx < tmpLandmarkNodeIds.length; lmIdx++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new RuntimeException("Thread was interrupted");
            }
            int lmNodeId = tmpLandmarkNodeIds[lmIdx];
            CoreLandmarkExplorer explorer = new CoreLandmarkExplorer(graph, this, lmWeighting, traversalMode);
            explorer.initFrom(lmNodeId, 0);
            EdgeFilterSequence coreEdgeFilter = new EdgeFilterSequence();
            coreEdgeFilter.add(new CoreAndBlockedEdgesFilter(encoder, false, true, blockedEdges));
            coreEdgeFilter.add(landmarksFilter);
            explorer.setFilter(coreEdgeFilter);
            explorer.runAlgo(true, coreEdgeFilter);
            explorer.initLandmarkWeights(lmIdx, lmNodeId, lmRowLength, fromOffset);

            // set subnetwork id to all explored nodes, but do this only for the first landmark
            if (lmIdx == 0 && explorer.setSubnetworks(subnetworks, subnetworkId))
                return false;

            explorer = new CoreLandmarkExplorer(graph, this, lmWeighting, traversalMode);
            explorer.initTo(lmNodeId, 0);
            EdgeFilterSequence coreEdgeFilterBWD = new EdgeFilterSequence();
            coreEdgeFilterBWD.add(new CoreAndBlockedEdgesFilter(encoder, true, false, blockedEdges));
            coreEdgeFilterBWD.add(landmarksFilter);
            explorer.setFilter(coreEdgeFilterBWD);
            explorer.runAlgo(false, coreEdgeFilterBWD);
            explorer.initLandmarkWeights(lmIdx, lmNodeId, lmRowLength, toOffset);

            if (lmIdx == 0 && explorer.setSubnetworks(subnetworks, subnetworkId))
                return false;

            if (logDetails && lmIdx % logOffset == 0 && LOGGER.isInfoEnabled())
                LOGGER.info(String.format("Set landmarks weights [%s]. Progress %d%%", lmWeighting, (int) (100.0 * lmIdx / tmpLandmarkNodeIds.length)));
        }

        // TODO (Peter TODO) set weight to SHORT_MAX if entry has either no 'from' or no 'to' entry
        landmarkIDs.add(tmpLandmarkNodeIds);
        return true;
    }

    @Override
    public boolean loadExisting() {
        if (isInitialized())
            throw new IllegalStateException("Cannot call PrepareCoreLandmarks.loadExisting if already initialized");
        if (landmarkWeightDA.loadExisting()) {
            if (!subnetworkStorage.loadExisting())
                throw new IllegalStateException("landmark weights loaded but not the subnetworks!?");

            int nodes = landmarkWeightDA.getHeader(0);
            if (nodes != core.getCoreNodes())
                throw new IllegalArgumentException(
                        "Cannot load landmark data as written for different graph storage with " + nodes
                                + " nodes, not " + core.getCoreNodes());
            landmarks = landmarkWeightDA.getHeader(4);
            int subnetworks = landmarkWeightDA.getHeader(8);
            factor = landmarkWeightDA.getHeader(12) / DOUBLE_MLTPL;
            lmRowLength = landmarks * 4L;
            long maxBytes = lmRowLength * nodes;
            long bytePos = maxBytes;

            // in the first subnetwork 0 there are no landmark IDs stored
            for (int j = 0; j < subnetworks; j++) {
                int[] tmpLandmarks = new int[landmarks];
                for (int i = 0; i < tmpLandmarks.length; i++) {
                    tmpLandmarks[i] = landmarkWeightDA.getInt(bytePos);
                    bytePos += 4;
                }
                landmarkIDs.add(tmpLandmarks);
            }

            initialized = true;
            return true;
        }
        return false;
    }

    /**
     * Specify the maximum possible value for your used area. With this maximum weight value you can influence the storage
     * precision for your weights that help A* finding its way to the goal. The same value is used for all subnetworks.
     * Note, if you pick this value too big then too similar weights are stored
     * (some bits of the storage capability will be left unused) which could lead to suboptimal routes.
     * If too low then far away values will have the same maximum value associated ("maxed out") leading to bad performance.
     *
     * @param maxWeight use a negative value to automatically determine this value.
     */
    public CoreLandmarkStorage setMaximumWeight(double maxWeight) {
        if (maxWeight > 0) {
            this.factor = maxWeight / PRECISION;
            if (Double.isInfinite(factor) || Double.isNaN(factor))
                throw new IllegalStateException(
                        "Illegal factor " + factor + " calculated from maximum weight " + maxWeight);
        }
        return this;
    }

    /**
     * By default do not log many details.
     */
    public void setLogDetails(boolean logDetails) {
        this.logDetails = logDetails;
    }

    /**
     * This method forces the landmark preparation to skip the landmark search and uses the specified landmark list instead.
     * Useful for manual tuning of larger areas to safe import time or improve quality.
     */
    public CoreLandmarkStorage setLandmarkSuggestions(List<LandmarkSuggestion> landmarkSuggestions) {
        if (landmarkSuggestions == null)
            throw new IllegalArgumentException("landmark suggestions cannot be null");

        this.landmarkSuggestions = landmarkSuggestions;
        return this;
    }

    /**
     * This method sets the required number of nodes of a subnetwork for which landmarks should be calculated. Every
     * subnetwork below this count will be ignored.
     */
    public void setMinimumNodes(int minimumNodes) {
        this.minimumNodes = minimumNodes;
    }

    /**
     * @see #setMinimumNodes(int)
     */
    public int getMinimumNodes() {
        return minimumNodes;
    }

    SubnetworkStorage getSubnetworkStorage() {
        return subnetworkStorage;
    }

    /**
     * This weighting is used for the selection heuristic and is per default not the weighting specified in the contructor.
     * The special weighting leads to a much better distribution of the landmarks and results in better response times.
     */
    public void setLMSelectionWeighting(Weighting lmSelectionWeighting) {
        this.lmSelectionWeighting = lmSelectionWeighting;
    }

    public Weighting getLmSelectionWeighting() {
        return lmSelectionWeighting;
    }

    /**
     * This method returns the weighting for which the landmarks are originally created
     */
    public Weighting getWeighting() {
        return weighting;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * This method specifies the polygons which should be used to split the world wide area to improve performance and
     * quality in this scenario.
     */
    public void setSpatialRuleLookup(SpatialRuleLookup ruleLookup) {
        this.ruleLookup = ruleLookup;
    }

    /**
     * This method makes edges crossing the specified border inaccessible to split a bigger area into smaller subnetworks.
     * This is important for the world wide use case to limit the maximum distance and also to detect unreasonable routes faster.
     */
    protected IntHashSet findBorderEdgeIds(SpatialRuleLookup ruleLookup) {
        AllEdgesIterator allEdgesIterator = graph.getAllEdges();
        NodeAccess nodeAccess = graph.getNodeAccess();
        IntHashSet inaccessible = new IntHashSet();
        while (allEdgesIterator.next()) {
            int adjNode = allEdgesIterator.getAdjNode();
            SpatialRule ruleAdj = ruleLookup.lookupRule(nodeAccess.getLatitude(adjNode),
                    nodeAccess.getLongitude(adjNode));

            int baseNode = allEdgesIterator.getBaseNode();
            SpatialRule ruleBase = ruleLookup.lookupRule(nodeAccess.getLatitude(baseNode),
                    nodeAccess.getLongitude(baseNode));
            if (ruleAdj != ruleBase) {
                inaccessible.add(allEdgesIterator.getEdge());
            }
        }
        return inaccessible;
    }

    /**
     * The factor is used to convert double values into more compact int values.
     */
    public double getFactor() {
        return factor;
    }

    /**
     * @return the weight from the landmark to the specified node. Where the landmark integer is not
     * a node ID but the internal index of the landmark array.
     */
    public int getFromWeight(int landmarkIndex, int node) {
        int res = (int) landmarkWeightDA.getShort((long) coreNodeIdMap.get(node) * lmRowLength + landmarkIndex * 4 + fromOffset)
                & 0x0000FFFF;
        if (res < 0)
            throw new AssertionError("Negative to weight " + res + ", landmark index:" + landmarkIndex + ", node:" + node);
        if (res == SHORT_INFINITY)
            // TODO can happen if endstanding oneway
            // we should set a 'from' value to SHORT_MAX if the 'to' value was already set to find real bugs
            // and what to return? Integer.MAX_VALUE i.e. convert to Double.pos_infinity upstream?
            return SHORT_MAX;
            // TODO if(res == MAX) fallback to beeline approximation!?

        return res;
    }

    /**
     * @return the weight from the specified node to the landmark (specified *as index*)
     */
    public int getToWeight(int landmarkIndex, int node) {
        int res = (int) landmarkWeightDA.getShort((long) coreNodeIdMap.get(node) * lmRowLength + landmarkIndex * 4 + toOffset)
                & 0x0000FFFF;
        if (res < 0)
            throw new AssertionError("Negative to weight " + res + ", landmark index:" + landmarkIndex + ", node:" + node);
        if (res == SHORT_INFINITY)
            return SHORT_MAX;
        //            throw new IllegalStateException("Do not call getToWeight for wrong landmark[" + landmarkIndex + "]=" + landmarkIDs[landmarkIndex] + " and node " + node);

        return res;
    }

    // Short.MAX_VALUE = 2^15-1 but we have unsigned short so we need 2^16-1
    protected static final int SHORT_INFINITY = Short.MAX_VALUE * 2 + 1;
    // We have large values that do not fit into a short, use a specific maximum value
    private static final int SHORT_MAX = SHORT_INFINITY - 1;

    /**
     * @return false if the value capacity was reached and instead of the real value the SHORT_MAX was stored.
     */
    final boolean setWeight(long pointer, double value) {
        double tmpVal = value / factor;
        if (tmpVal > Integer.MAX_VALUE)
            throw new UnsupportedOperationException(
                    "Cannot store infinity explicitely, pointer=" + pointer + ", value: " + value);

        if (tmpVal >= SHORT_MAX) {
            landmarkWeightDA.setShort(pointer, (short) SHORT_MAX);
            return false;
        } else {
            landmarkWeightDA.setShort(pointer, (short) tmpVal);
            return true;
        }
    }

    boolean isInfinity(long pointer) {
        return ((int) landmarkWeightDA.getShort(pointer) & 0x0000FFFF) == SHORT_INFINITY;
    }

    int calcWeight(EdgeIteratorState edge, boolean reverse) {
        return (int) (weighting.calcWeight(edge, reverse, EdgeIterator.NO_EDGE) / factor);
    }

    // From all available landmarks pick just a few active ones
    public boolean initActiveLandmarks(int fromNode, int toNode, int[] activeLandmarkIndices, int[] activeFroms,
                                          int[] activeTos, boolean reverse) {
        if (fromNode < 0 || toNode < 0)
            throw new IllegalStateException(
                    "from " + fromNode + " and to " + toNode + " nodes have to be 0 or positive to init landmarks");

        int subnetworkFrom = subnetworkStorage.getSubnetwork(coreNodeIdMap.get(fromNode));
        int subnetworkTo = subnetworkStorage.getSubnetwork(coreNodeIdMap.get(toNode));

        if (subnetworkFrom <= UNCLEAR_SUBNETWORK || subnetworkTo <= UNCLEAR_SUBNETWORK)
            return false;
        if (subnetworkFrom != subnetworkTo) {
            throw new ConnectionNotFoundException("Connection between locations not found. Different subnetworks "
                    + subnetworkFrom + " vs. " + subnetworkTo, new HashMap<String, Object>());
        }

        int[] tmpIDs = landmarkIDs.get(subnetworkFrom);

        // kind of code duplication to approximate
        List<Map.Entry<Integer, Integer>> list = new ArrayList<>(tmpIDs.length);
        for (int lmIndex = 0; lmIndex < tmpIDs.length; lmIndex++) {
            int fromWeight = getFromWeight(lmIndex, toNode) - getFromWeight(lmIndex, fromNode);
            int toWeight = getToWeight(lmIndex, fromNode) - getToWeight(lmIndex, toNode);

            list.add(new MapEntry<>(reverse ? Math.max(-fromWeight, -toWeight) : Math.max(fromWeight, toWeight),
                    lmIndex));
        }

        Collections.sort(list, SORT_BY_WEIGHT);

        if (activeLandmarkIndices[0] >= 0) {
            IntHashSet set = new IntHashSet(activeLandmarkIndices.length);
            set.addAll(activeLandmarkIndices);
            int existingLandmarkCounter = 0;
            final int COUNT = Math.min(activeLandmarkIndices.length - 2, 2);
            for (int i = 0; i < activeLandmarkIndices.length; i++) {
                if (i >= activeLandmarkIndices.length - COUNT + existingLandmarkCounter) {
                    // keep at least two of the previous landmarks (pick the best)
                    break;
                } else {
                    activeLandmarkIndices[i] = list.get(i).getValue();
                    if (set.contains(activeLandmarkIndices[i]))
                        existingLandmarkCounter++;
                }
            }

        } else {
            for (int i = 0; i < activeLandmarkIndices.length; i++) {
                activeLandmarkIndices[i] = list.get(i).getValue();
            }
        }

        // store weight values of active landmarks in 'cache' arrays
        initActiveLandmarkWeights(toNode, activeLandmarkIndices, activeFroms, activeTos);

        return true;
    }

    // precompute weights from and to active landmarks
    public void initActiveLandmarkWeights(int toNode, int[] activeLandmarkIndices, int[] activeFroms,  int[] activeTos) {
        for (int i = 0; i < activeLandmarkIndices.length; i++) {
            int lmIndex = activeLandmarkIndices[i];
            activeFroms[i] = getFromWeight(lmIndex, toNode);
            activeTos[i] = getToWeight(lmIndex, toNode);
        }
    }
    public int getLandmarkCount() {
        return landmarks;
    }

    public int[] getLandmarks(int subnetwork) {
        return landmarkIDs.get(subnetwork);
    }

    /**
     * @return the number of subnetworks that have landmarks
     */
    public int getSubnetworksWithLandmarks() {
        return landmarkIDs.size();
    }

    public boolean isEmpty() {
        return landmarkIDs.size() < 2;
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        for (int[] ints : landmarkIDs) {
            if (str.length() > 0)
                str.append(", ");
            str.append(Arrays.toString(ints));
        }
        return str.toString();
    }

    /**
     * @return the calculated landmarks as GeoJSON string.
     */
    public String getLandmarksAsGeoJSON() {
        NodeAccess na = graph.getNodeAccess();
        StringBuilder str = new StringBuilder();
        for (int subnetwork = 1; subnetwork < landmarkIDs.size(); subnetwork++) {
            int[] lmArray = landmarkIDs.get(subnetwork);
            for (int lmIdx = 0; lmIdx < lmArray.length; lmIdx++) {
                int index = lmArray[lmIdx];
                if (str.length() > 0)
                    str.append(",");
                str.append("{ \"type\": \"Feature\", \"geometry\": {\"type\": \"Point\", \"coordinates\": [")
                    .append(na.getLon(index)).append(", ").append(na.getLat(index)).append("]},")
                    .append("  \"properties\":{\"node_index\":").append(index).append(",").append("\"subnetwork\":")
                    .append(subnetwork).append(",").append("\"lm_index\":").append(lmIdx).append("}}");
            }
        }
        return "{ \"type\": \"FeatureCollection\", \"features\": [" + str + "]}";
    }


    @Override
    public LandmarkStorage create(long byteCount) {
        throw new IllegalStateException("Do not call LandmarkStore.create directly");
    }

    @Override
    public void flush() {
        landmarkWeightDA.flush();
        subnetworkStorage.flush();
    }

    @Override
    public void close() {
        landmarkWeightDA.close();
        subnetworkStorage.close();
    }

    @Override
    public boolean isClosed() {
        return landmarkWeightDA.isClosed();
    }

    @Override
    public long getCapacity() {
        return landmarkWeightDA.getCapacity() + subnetworkStorage.getCapacity();
    }

    /**
     * This class is used to calculate landmark location (equally distributed).
     */
    protected class CoreLandmarkExplorer extends DijkstraBidirectionRef {
        private int lastNode;
        private boolean fromMode;
        private final CoreLandmarkStorage lms;

        public CoreLandmarkExplorer(Graph g, CoreLandmarkStorage lms, Weighting weighting, TraversalMode tMode) {
            super(g, weighting, tMode);
            this.lms = lms;
        }

        private GHPoint createPoint(Graph graph, int nodeId) {
            return new GHPoint(graph.getNodeAccess().getLatitude(nodeId), graph.getNodeAccess().getLongitude(nodeId));
        }

        public void setFilter(EdgeFilter filter) {
            outEdgeExplorer = core.createEdgeExplorer(filter);
            inEdgeExplorer = core.createEdgeExplorer(filter);
            this.setEdgeFilter(filter);
        }

        public int getFromCount() {
            return bestWeightMapFrom.size();
        }

        int getToCount() {
            return bestWeightMapTo.size();
        }

        public int getLastNode() {
            return lastNode;
        }

        public void initFrom(int from, double weight) {
            super.initFrom(from, weight);
        }

        public void initTo(int to, double weight) {
            super.initTo(to, weight);
        }

        public void runAlgo(boolean from, EdgeFilter filter) {
            // no path should be calculated
            setUpdateBestPath(false);
            this.setEdgeFilter(filter);
            // set one of the bi directions as already finished
            if (from)
                finishedTo = true;
            else
                finishedFrom = true;

            this.fromMode = from;
            super.runAlgo();
        }

        @Override
        public boolean finished() {
            if (fromMode) {
                lastNode = currFrom.adjNode;
                return finishedFrom;
            } else {
                lastNode = currTo.adjNode;
                return finishedTo;
            }
        }

        public boolean setSubnetworks(final byte[] subnetworks, final int subnetworkId) {
            if (subnetworkId > 127)
                throw new IllegalStateException("Too many subnetworks " + subnetworkId);

            final AtomicBoolean failed = new AtomicBoolean(false);
            IntObjectMap<SPTEntry> map = fromMode ? bestWeightMapFrom : bestWeightMapTo;
            map.forEach((IntObjectPredicate<SPTEntry>) (nodeId, value) -> {
                int sn = subnetworks[coreNodeIdMap.get(nodeId)];
                if (sn != subnetworkId) {
                    if (sn != UNSET_SUBNETWORK && sn != UNCLEAR_SUBNETWORK) {
                        // this is ugly but can happen in real world, see testWithOnewaySubnetworks
                        LOGGER.error("subnetworkId for node " + nodeId + " (" + createPoint(graph, nodeId)
                                + ") already set (" + sn + "). " + "Cannot change to " + subnetworkId);

                        failed.set(true);
                        return false;
                    }

                    subnetworks[coreNodeIdMap.get(nodeId)] = (byte) subnetworkId;
                }
                return true;
            });
            return failed.get();
        }

        public void initLandmarkWeights(final int lmIdx, int lmNodeId, final long rowSize, final int offset) {
            IntObjectMap<SPTEntry> map = fromMode ? bestWeightMapFrom : bestWeightMapTo;
            final AtomicInteger maxedout = new AtomicInteger(0);
            final Map.Entry<Double, Double> finalMaxWeight = new MapEntry<>(0d, 0d);

            map.forEach((IntObjectProcedure<SPTEntry>) (nodeId, b) -> {
                nodeId = coreNodeIdMap.get(nodeId);
                if (!lms.setWeight(nodeId * rowSize + lmIdx * 4 + offset, b.weight)) {
                    maxedout.incrementAndGet();
                    finalMaxWeight.setValue(Math.max(b.weight, finalMaxWeight.getValue()));
                }
            });

            if ((double) maxedout.get() / map.size() > 0.1 && LOGGER.isInfoEnabled()) {
                LOGGER.warn(new StringBuilder().append("landmark ")
                    .append(lmIdx).append(" (").append(nodeAccess.getLatitude(lmNodeId)).append(",")
                    .append(nodeAccess.getLongitude(lmNodeId)).append("): ").append("too many weights were maxed out (")
                    .append(maxedout.get()).append("/").append(map.size()).append("). Use a bigger factor than ")
                    .append(lms.factor).append(". For example use the following in the config.properties: weighting=")
                    .append(weighting.getName()).append("|maximum=").append(finalMaxWeight.getValue() * 1.2).toString());
            }
        }
    }

    /**
     * Sort landmark by weight and let maximum weight come first, to pick best active landmarks.
     */
    private static final Comparator<Map.Entry<Integer, Integer>> SORT_BY_WEIGHT = (o1, o2) -> Integer.compare(o2.getKey(), o1.getKey());

    protected static class RequireBothDirectionsEdgeFilter implements EdgeFilter {

        private FlagEncoder flagEncoder;

        public RequireBothDirectionsEdgeFilter(FlagEncoder flagEncoder) {
            this.flagEncoder = flagEncoder;
        }

        @Override
        public boolean accept(EdgeIteratorState edgeState) {
            return edgeState.get(flagEncoder.getAccessEnc()) && edgeState.getReverse(flagEncoder.getAccessEnc());
        }
    }

    /**
     * Filter out blocked edges and edges that are NOT in the core
     */

    private class CoreAndBlockedEdgesFilter implements EdgeFilter {
        private final IntHashSet blockedEdges;
        private final FlagEncoder encoder;
        private final boolean fwd;
        private final boolean bwd;
        private final int coreNodeLevel;
        private final int maxNodes;

        public CoreAndBlockedEdgesFilter(FlagEncoder encoder, boolean bwd, boolean fwd, IntHashSet blockedEdges) {
            this.maxNodes = core.getNodes();
            this.coreNodeLevel = this.maxNodes + 1;
            this.encoder = encoder;
            this.bwd = bwd;
            this.fwd = fwd;
            this.blockedEdges = blockedEdges;
        }

        @Override
        public final boolean accept(EdgeIteratorState iter) {
            int base = iter.getBaseNode();
            int adj = iter.getAdjNode();

            if (base >= maxNodes || adj >= maxNodes)
                return true;
            //Accept only edges that are in core
            if(core.getLevel(base) < coreNodeLevel || core.getLevel(adj) < coreNodeLevel)
                return false;

            boolean blocked = blockedEdges.contains(iter.getEdge());
            return fwd && iter.get(encoder.getAccessEnc()) && !blocked || bwd && iter.getReverse(encoder.getAccessEnc()) && !blocked;
        }

        public boolean acceptsBackward() {
            return bwd;
        }

        public boolean acceptsForward() {
            return fwd;
        }

        @Override
        public String toString() {
            return encoder.toString() + ", bwd:" + bwd + ", fwd:" + fwd;
        }
    }

    /**
     * Filter out edges that are NOT in the core and then super.accept
     */
    protected final class CoreAndRequireBothDirectionsEdgeFilter extends RequireBothDirectionsEdgeFilter {
        private final int coreNodeLevel;

        public CoreAndRequireBothDirectionsEdgeFilter(FlagEncoder flagEncoder) {
            super(flagEncoder);
            this.coreNodeLevel = core.getNodes() + 1;
        }

        @Override
        public boolean accept(EdgeIteratorState iter) {
            if(core.getLevel(iter.getBaseNode()) < coreNodeLevel || core.getLevel(iter.getAdjNode()) < coreNodeLevel)
                return false;
            return super.accept(iter);
        }
    }
}
