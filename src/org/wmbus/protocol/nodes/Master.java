package org.wmbus.protocol.nodes;

//import desmoj.core.simulator.*;
//import co.paralleluniverse.fibers.SuspendExecution;

import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.pmw.tinylog.Logger;
import org.wmbus.protocol.infrastructure.ECCTable;
import org.wmbus.protocol.messages.*;
import org.wmbus.protocol.simulation.WMBusSimulation;
import yang.simulation.network.MasterGraphNode;

import java.io.PrintWriter;
import java.text.DecimalFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public class Master extends WMbusDevice {

    private final SimpleWeightedGraph<MasterGraphNode, DefaultWeightedEdge> networkGraphECC;

    private List<MasterGraphNode> path;

    public PrintWriter log;
    public PrintWriter band_log;

    private Request sending;
    private MasterGraphNode endNode;
    private ArrayDeque<Integer> pathEncoded;
    private DecimalFormat formatter = new DecimalFormat("#,###.00");
    private Integer fixedNode;
    public PrintWriter log_fault;

    public Master(WMBusSimulation simulation, SimpleWeightedGraph<MasterGraphNode, DefaultWeightedEdge> graph) {
        super(simulation,0);
        this.networkGraphECC = graph;

    }



    private ArrayDeque<Integer> encodePath(List<MasterGraphNode> path) {
        ArrayDeque<Integer> hopList = new ArrayDeque<Integer>();

        for (MasterGraphNode node : path) {
            hopList.add(node.getStaticAddress());
        }
        return hopList;
    }

    public void printGraph(SimpleWeightedGraph<MasterGraphNode, DefaultWeightedEdge> networkGraphECC){
        for (DefaultWeightedEdge e: networkGraphECC.edgeSet()){
            Logger.trace("From: "+networkGraphECC.getEdgeSource(e)+" To: "+networkGraphECC.getEdgeTarget(e)+" With  weight: "+networkGraphECC.getEdgeWeight(e));
        }

    }

    public void lifeCycle()  {

        Master m = this;
        MasterGraphNode masterNode = new MasterGraphNode(0);
        // Generate fixed list node.
        ArrayList<Integer> dest = getDestinations(this.simulation.getwMbusNetwork().getNodes().size()); // without master.

        Integer fixedNodeIndex = 0;

        /*
         * come descrivi una pecora brutta ?
		 * bela fuori ...e  bella dentro ...
         */
        Logger.trace("WMBusSimulation starts");
        while (true) {
            //fixedNode = randomGen.nextInt(graph.vertexSet().size() - 1) +1;
            if (fixedNodeIndex.equals(dest.size())) {
                fixedNodeIndex = 0;
                fixedNode = dest.get(fixedNodeIndex);
                fixedNodeIndex++;
            } else {
                fixedNode = dest.get(fixedNodeIndex);
                fixedNodeIndex++;
            }

            // this.printGraph(this.networkGraphECC);
            //TwoApproxMetricTSP salesman = new TwoApproxMetricTSP<MasterGraphNode, DefaultWeightedEdge>();
            DijkstraShortestPath dijkstraPaths = new DijkstraShortestPath<MasterGraphNode, DefaultWeightedEdge>(this.networkGraphECC);
            //salesman.getTour(graph)
            // dijkstraPaths.getPaths(masterNode);
            endNode = new MasterGraphNode(fixedNode); // chooose destination and apply dijkstra
            //System.out.println("Master ask data to "+fixedNode);
            if (endNode == null) {
                throw new IllegalArgumentException();
            }
            /**
             * The dijkstraPaths path.
             */
            GraphPath p;
            try {
                    p = dijkstraPaths.getPath(masterNode,endNode);
                    path = p.getVertexList();
            } catch (Exception e) {
                //throw new SUSPEND;
                Logger.error("Avoid packet with endnode: "+(fixedNodeIndex-1));
                //System.out.println(e.getMessage());
                continue; // Maybe happen no path ok.
                //continue;
            }

            if (p == null){
                Logger.error("Path not found");
                continue;
            }

            try {
                path.remove(0);
            } catch (Exception e) {
                //System.out.println("No master node "+fixedNode);
                Logger.error("Node master not found");
                throw new IllegalArgumentException();
                //continue;
                // the fuck?
            }

            /**
             * Prepare packet 1 represents the type of packet token represents
             * the sequence number. 20 payload size. 0 the source path the list
             * of nodes.
             */
            pathEncoded = encodePath(path);
            Logger.info(this.simulation.getResults().masterSentMessage+ "");
            Logger.trace(pathEncoded);
            Logger.trace("Weight:"+p.getWeight());
            //System.out.println(pathEncoded);

            this.simulation.getResults().masterSumPath+=pathEncoded.size();

            sending = new Request(this.simulation,0,pathEncoded);
            double answer = 0;
            // update master number of messages sent.
            this.simulation.getResults().masterSentMessage+=1;

            answer = this.transmit(sending); // Going down.

            if (answer == WMBusCommunicationState.TIMEOUT){
                this.simulation.getResults().masterTrasmissionFaultWithNoUpdate++;
                this.triggerTimeout();
            }

            if (this.simulation.getResults().masterSentMessage >= this.simulation.getwMbusConfig().CONF_LASTING) {
                return; // finish simulation.
            }
  

         }

    }

    /**
     * Update internal graph structure.
     * @param destination
     */
    @Override
    public void updateECCStructures(int destination){
        super.updateECCStructures(destination);
        DefaultWeightedEdge edge = this.simulation.getwMbusNetwork().getEccGraph().getEdge(new MasterGraphNode(0),new MasterGraphNode(destination));
        //System.out.println("Update link state from to "+destination+" with error maximum");
        this.networkGraphECC.setEdgeWeight(edge, WMBusCommunicationState.TIMEOUT);
    }

    /**
     * Receive a packet
     * @param message
     */
    @Override
    public void receive(WMbusMessage message)  {
        super.receive(message);
        double tras_result = 0; // fixed value.
        // WMBusStats.hopCount += 1;

        // Check message type.
        if (message.getMessageType()== WMBusPacketType.PACKET_REQUEST) {
            // no need to do anything.
        } else
        if (message.getMessageType()== WMBusPacketType.PACKET_RESPONSE) {

            Response res = (Response) message;
            Logger.trace("Data: "+((Response) message).getData());
            // No data means error.
            if (res.getData()==0){
                this.simulation.getResults().masterTrasmissionFaultWithUpdate++;
            }else{
                this.simulation.getResults().masterTrasmissionSuccess++;
            }
            for(ECCTable entry : res.getECCTables()){
                for (Integer destination: entry.getEntries().keySet()){
                    DefaultWeightedEdge edge = this.simulation.getwMbusNetwork().getEccGraph().getEdge(new MasterGraphNode(entry.getNode()),new MasterGraphNode(destination));
                    Logger.trace("Update link state from "+entry.getNode()+" to "+destination+" with error "+entry.getEntries().get(destination));
                    this.simulation.getResults().masterAvgNumberOfUpdatedLink++;
                    this.networkGraphECC.setEdgeWeight(edge,entry.getEntries().get(destination));
                }
            }
        }
    }
    private ArrayList<Integer> getDestinations(int maxDest) {
        ArrayList<Integer> list = new ArrayList<Integer>();
        // 1 without master.
        for (int i = 1; i < maxDest;i++){
            list.add(i);
        }
        if (this.simulation.getwMbusConfig().CONF_DESTINATION_FETCH == this.simulation.getwMbusConfig().DESTINATION_FETCH_RANDOM){
            Collections.shuffle(list);
        }
        return list;
    }
}