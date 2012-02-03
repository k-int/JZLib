package com.k_int.sample;

import org.jzkit.z3950.util.ZEndpoint;
import org.jzkit.z3950.gen.v3.Z39_50_APDU_1995.*;
import org.jzkit.z3950.util.*;
import org.jzkit.z3950.Z3950Exception;
import org.jzkit.z3950.QueryModel.*;
import org.jzkit.search.util.RecordModel.*;
import org.jzkit.search.util.QueryModel.*;
import org.jzkit.a2j.codec.util.*;
import org.jzkit.a2j.gen.AsnUseful.*;
import org.jzkit.a2j.codec.util.OIDRegister;
import java.util.*;

/**
 * Example Groovy class.
 */
public class CopacClient implements APDUListener {

  public String host;
  public int port=210;

  public static final int NO_CONNECTION = 1;
  public static final int CONNECTING = 2;
  public static final int CONNECTED = 3;
  public static final String NO_RS_NAME="default";
  int session_status = NO_CONNECTION;
  private HashMap responses = new HashMap();
  private Hashtable dbinfo = new Hashtable();

  private boolean supports_named_result_sets = true;

  OIDRegister reg = new org.jzkit.a2j.codec.util.OIDRegister("/a2j.properties");

  // These are the rules for converting friendly names to bib-1 use attributes
  InternalToType1ConversionRules rules = new org.jzkit.z3950.QueryModel.PropsBasedInternalToType1ConversionRules("/InternalToType1Rules.properties");

  ZEndpoint assoc = null;



  public static void main(String[] params) throws org.jzkit.z3950.Z3950Exception, org.jzkit.search.util.QueryModel.InvalidQueryException {
    CopacClient client = new CopacClient();
    client.host = "z3950.copac.ac.uk";
    client.port = 210;
    ArrayList dbnames = new ArrayList();
    dbnames.add("COPAC");

    client.connect();

    // @attr 1=7 ( 1==Use Attr, 7=ISBN)
    SearchResponse_type sr = client.sendSearch(new org.jzkit.search.util.QueryModel.PrefixString.PrefixString("@attrset bib-1 @attr 1=7 \"0713902191\""),
                                               null,      // refid
                                               "default", // setname
                                               "b",
                                               "usmarc",
                                               dbnames);

    if ( sr.searchStatus == true ) {
      System.out.println("Search response OK. result count : "+sr.resultCount);
      // Dump the records
      PresentResponse_type pr = client.sendPresent(1,1,"f","default","xml");
      Records_type r = pr.records;
      client.displayRecords(r);
    }
    else {
      System.out.println("Search Failure");
    }

    client.disconnect();
  }


  public CopacClient() {
  }


  // public InitializeResponse_type connect(String hostname,
  //                                       int portnum,
  //                                       int auth_type,      // 0 = none, 1=Anonymous, 2=open, 3=idpass
  //                                       String principal,   // for open, the access string, for idpass, the id
  //                                       String group,       // group
  //                                       String credentials) // password
  public InitializeResponse_type connect() {

    System.out.println("Connect");
    InitializeResponse_type retval = null;

    try {
      disconnect();

      System.out.println("Create listener & encoder");
      assoc = new ZEndpoint(reg);
      assoc.setHost(host);
      assoc.setPort(port);

      // Convert incoming observer/observable notifications into PDU notifications.
      assoc.getPDUAnnouncer().addObserver( new GenericEventToOriginListenerAdapter(this) );

      // Look out for the init response
      PDUTypeAvailableSemaphore s = new PDUTypeAvailableSemaphore(PDU_type.initresponse_CID, assoc.getPDUAnnouncer() );

      assoc.start();

      try {
        s.waitForCondition(20000); // Wait up to 20 seconds for an init response
        retval = (InitializeResponse_type) s.the_pdu.o;
      }
      catch ( Exception e ) {
        System.out.println("Problem whilst waiting for init response....");
        e.printStackTrace();
      }
      finally {
        s.destroy();
      }
      System.out.println( "completed");
    }
    catch(Exception e) {
      e.printStackTrace();
    }

    return retval;
  }

  public void disconnect() {
    if ( null != assoc ) {
      System.out.println("Closing existing listener");
      try {
        assoc.shutdown();
      }
      catch ( java.io.IOException ioe ) {
        ioe.printStackTrace();
      }
    }
  }

  public SearchResponse_type sendSearch(QueryModel query, 
                                        String refid,
                                        String setname,
                                        String elements,
                                        String record_syntax,
                                        ArrayList db_names) throws Z3950Exception, InvalidQueryException {
    SearchResponse_type retval = null;

    if ( refid == null )
      refid = assoc.genRefid("Search");

    // ReferencedPDUAvaialableSemaphore s = new ReferencedPDUAvaialableSemaphore(refid, assoc.getPDUAnnouncer() );
    PDUTypeAvailableSemaphore s = new PDUTypeAvailableSemaphore(PDU_type.searchresponse_CID, assoc.getPDUAnnouncer() );

    try {

      Z3950QueryModel qry = null;

      // First thing we need to do is check that query is an instanceof Type1Query or to do a conversion...
      if ( query instanceof Z3950QueryModel )
        qry = (Z3950QueryModel) query;
      else
        qry = Type1QueryModelBuilder.buildFrom(query, "utf-8", reg, rules);
      
      assoc.sendSearchRequest(db_names, 
                              qry.toASNType(),
                              refid, 
                              0, 1, 1, true,   // ssub, lslb, mspn was 3,5,10
                              ( supports_named_result_sets == true ? setname : NO_RS_NAME ),   // Result set name
                              elements, 
                              elements, 
                              reg.oidByName(record_syntax));

      s.waitForCondition(20000); // Wait up to 20 seconds for a message of type SearchResponse
      retval = (SearchResponse_type) s.the_pdu.o;
    }
    catch ( java.io.IOException ioe )
    {
      // Problem with comms sending PDU...
      ioe.printStackTrace();
    }
    catch ( TimeoutExceededException tee )
    {
      tee.printStackTrace();
    }
    finally
    {
      s.destroy();
    }


    return retval;
  }

  public PresentResponse_type sendPresent(long start, long count, String element_set_name, String setname, String record_syntax) {
    PresentResponse_type retval = null;
    String refid = assoc.genRefid("Present");

    PDUTypeAvailableSemaphore s = new PDUTypeAvailableSemaphore(PDU_type.presentresponse_CID, assoc.getPDUAnnouncer() );
    // ReferencedPDUAvaialableSemaphore s = new ReferencedPDUAvaialableSemaphore(refid, assoc.getPDUAnnouncer() );

    try {
      assoc.sendPresentRequest(refid,
                               ( supports_named_result_sets == true ? setname : NO_RS_NAME ),
                               start,
                               count,
                               new ExplicitRecordFormatSpecification(record_syntax,null,element_set_name));

      s.waitForCondition(20000); // Wait up to 20 seconds for a response
      retval = (PresentResponse_type) s.the_pdu.o;
    }
    catch ( TimeoutExceededException tee )
    {
      tee.printStackTrace();
    }
    catch ( Exception e )
    {
      e.printStackTrace();
    }
    finally
    {
      s.destroy();
    }

    System.err.println("Done present request");
    return retval;
  }


  /** 
   * Alternate sendSearch that simply passes along a search request PDU. Added for proxy server.
   */
  public SearchResponse_type sendSearch(PDU_type req) throws Z3950Exception, InvalidQueryException {
    SearchResponse_type retval = null;
    SearchRequest_type search_req = (SearchRequest_type)req.o;

    ReferencedPDUAvaialableSemaphore s = new ReferencedPDUAvaialableSemaphore(new String(search_req.referenceId), assoc.getPDUAnnouncer() );

    try
    {
      assoc.encodeAndSend(req);

      s.waitForCondition(20000); // Wait up to 20 seconds for a response
      retval = (SearchResponse_type) s.the_pdu.o;
    }
    catch ( java.io.IOException ioe )
    {
      ioe.printStackTrace();
    }
    catch ( TimeoutExceededException tee )
    {
      tee.printStackTrace();
    }
    finally
    {
      s.destroy();
    }

    return retval;
  }

  // Notification Handlers
  public void incomingAPDU(APDUEvent e){
    System.out.println("Incoming apdu");
  }

  public void incomingInitRequest(APDUEvent e){
    System.out.println("Incoming InitRequest");
    // Preparation for synchronous Retrieval API
    notifyAll();
  }

  public void incomingInitResponse(APDUEvent e) {
    System.out.println("Incoming InitResponse");

    InitializeResponse_type init_response = (InitializeResponse_type) (e.getPDU().o);
    responses.put(init_response.referenceId, init_response);
    session_status = CONNECTED;

    // log.fine("Incoming init response "+init_response.referenceId);

    if ( init_response.result.booleanValue() == true ) {
      if ( init_response.options.isSet(14) )
        System.out.println("Target supports named result sets");
      else
      {
        System.out.println("Target does not support named result sets");
        supports_named_result_sets = false;
      }
    }

    synchronized(this){
      notifyAll();
    }
  }

  public void incomingSearchRequest(APDUEvent e)  {
    // Preparation for synchronous Retrieval API
    synchronized(this){
      notifyAll();
    }
  }

  public void incomingSearchResponse(APDUEvent e) {
    SearchResponse_type search_response = (SearchResponse_type) e.getPDU().o;
    responses.put(search_response.referenceId, search_response);
    // log.fine("Incoming search response "+search_response.referenceId);

    synchronized(this){
      notifyAll();
    }
  }

  public void incomingPresentRequest(APDUEvent e) {
    // log.fine("Incoming PresentResponse");

    // Preparation for synchronous Retrieval API
    synchronized(this){
      notifyAll();
    }
  }

  public void incomingPresentResponse(APDUEvent e) {
    PresentResponse_type present_response = (PresentResponse_type) e.getPDU().o;
    responses.put(present_response.referenceId, present_response);

    synchronized(this){
      notifyAll();
    }
  }

  public void incomingDeleteResultSetRequest(APDUEvent e) {
    // Preparation for synchronous Retrieval API
    synchronized(this){
      notifyAll();
    }
  }

  public void incomingDeleteResultSetResponse(APDUEvent e) {
    // log.fine("Incoming DeleteResultSetResponse");

    // Preparation for synchronous Retrieval API
    synchronized(this){
      notifyAll();
    }
  }

  public void incomingAccessControlRequest(APDUEvent e) {
    // Preparation for synchronous Retrieval API
    synchronized(this) {
      notifyAll();
    }
  }

  public void incomingAccessControlResponse(APDUEvent e) {
    // System.err.System.out.println("Incoming AccessControlResponse");

    // Preparation for synchronous Retrieval API
    synchronized(this){
      notifyAll();
    }
  }

  public void incomingResourceControlRequest(APDUEvent e) {
    // Preparation for synchronous Retrieval API
    synchronized(this){
      notifyAll();
    }
  }

  public void incomingResourceControlResponse(APDUEvent e) {
    // System.err.System.out.println("Incoming ResourceControlResponse");

    // Preparation for synchronous Retrieval API
    synchronized(this) {
      notifyAll();
    }
  }

  public void incomingTriggerResourceControlRequest(APDUEvent e) {
    // Preparation for synchronous Retrieval API
    synchronized(this) {
      notifyAll();
    }
  }

  public void incomingResourceReportRequest(APDUEvent e) {
    // System.err.System.out.println("Incoming ResourceReportResponse");

    // Preparation for synchronous Retrieval API
    synchronized(this) {
      notifyAll();
    }
  }

  public void incomingResourceReportResponse(APDUEvent e) {
    // Preparation for synchronous Retrieval API
    synchronized(this) {
      notifyAll();
    }
  }

  public void incomingScanRequest(APDUEvent e) {
    // System.err.System.out.println("Incoming ScanResponse");

    // Preparation for synchronous Retrieval API
    synchronized(this) {
      notifyAll();
    }
  }

  public void incomingScanResponse(APDUEvent e) {
    // Preparation for synchronous Retrieval API
    synchronized(this) {
      notifyAll();
    }
  }

  public void incomingSortRequest(APDUEvent e) {
    // System.err.System.out.println("Incoming SortResponse");
    // Preparation for synchronous Retrieval API
    synchronized(this) {
      notifyAll();
    }
  }

  public void incomingSortResponse(APDUEvent e) {
    // Preparation for synchronous Retrieval API
    synchronized(this) {
      notifyAll();
    }
  }

  public void incomingSegmentRequest(APDUEvent e) {
    // System.err.System.out.println("Incoming SegmentResponse");

    // Preparation for synchronous Retrieval API
    synchronized(this){
      notifyAll();
    }
  }

  public void incomingExtendedServicesRequest(APDUEvent e) {
    // Preparation for synchronous Retrieval API
    synchronized(this){
      notifyAll();
    }
  }

  public void incomingExtendedServicesResponse(APDUEvent e) {
    // System.err.System.out.println("Incoming ExtendedServicesResponse");

    // Preparation for synchronous Retrieval API
    synchronized(this){
      notifyAll();
    }
  }

  public void incomingClose(APDUEvent e) {
    // System.err.System.out.println("Incoming close event: ");
    Close_type close_apdu = (Close_type) e.getPDU().o;
    // System.err.System.out.println("closeReason:"+close_apdu.closeReason);
    // System.err.System.out.println("diagnosticInformation:"+close_apdu.diagnosticInformation);

    // Preparation for synchronous Retrieval API
    synchronized(this){
      notifyAll();
    }
  }

  public void displayRecords(Records_type r) {
    if ( r != null ) {
      switch ( r.which ) {
        case Records_type.responserecords_CID:
            List v = (List)(r.o);
            int num_records = v.size();
            System.out.println("Response contains "+num_records+" Response Records");
            for ( Iterator recs = v.iterator(); recs.hasNext(); ) 
            {
                NamePlusRecord_type npr = (NamePlusRecord_type)(recs.next());

                if ( null != npr )
                {
                  System.out.print("["+npr.name+"] ");

                  switch ( npr.record.which )
                  {
                    case record_inline13_type.retrievalrecord_CID:
                      // RetrievalRecord is an external
                      EXTERNAL_type et = (EXTERNAL_type)npr.record.o;
                      // System.out.println("  Direct Reference="+et.direct_reference+"] ");
                      // dumpOID(et.direct_reference);
                      // Just rely on a toString method for now
                      if ( et.direct_reference.length == 6 )
                      {
                        switch(et.direct_reference[(et.direct_reference.length)-1])
                        {
                          case 1: // Unimarc
                            System.out.print("Unimarc ");
                            // DisplayISO2709((byte[])et.encoding.o);
                            break;
                          case 3: // CCF
                            // System.out.print("CCF ");
                            break;
                          case 10: // US Marc
                            System.out.print("USMarc: ");
                            // DisplayISO2709((byte[])et.encoding.o);
                            break;
                          case 11: // UK Marc
                            System.out.print("UkMarc ");
                            // DisplayISO2709((byte[])et.encoding.o);
                            break;
                          case 12: // Normarc
                            System.out.print("Normarc ");
                            // DisplayISO2709((byte[])et.encoding.o);
                            break;
                          case 13: // Librismarc
                            System.out.print("Librismarc ");
                            // DisplayISO2709((byte[])et.encoding.o);
                            break;
                          case 14: // Danmarc
                            System.out.print("Danmarc ");
                            // DisplayISO2709((byte[])et.encoding.o);
                            break;
                          case 15: // Finmarc
                            System.out.print("Finmarc ");
                            // DisplayISO2709((byte[])et.encoding.o);
                            break;
			  case 100: // Explain
			    // cat.debug("Explain record");
			    // Write display code....
			    break;
			  case 101: // SUTRS
			    System.out.print("SUTRS ");
			    System.out.println((String)et.encoding.o);
			    break;
			  case 102: // Opac
			    // cat.debug("Opac record");
			    // Write display code....
			    break;
			  case 105: // GRS1
			    System.out.print("GRS1 ");
			    // displayGRS((java.util.List)et.encoding.o);
			    break;
                          default:
			    System.out.print("Unknown.... ");
                            System.out.println(et.encoding.o.toString());
                            break;
                        }
                      }
                      else if ( ( et.direct_reference.length == 7 ) &&
                                ( et.direct_reference[5] == 109 ) )
                      {
                        switch(et.direct_reference[6])
                        {
                          case 3: // HTML
                            System.out.print("HTML ");
							String html_rec = null;
							if ( et.encoding.o instanceof byte[] )
								html_rec = new String((byte[])et.encoding.o);
							else
								html_rec = et.encoding.o.toString();                             
                            System.out.println(html_rec.toString());
                            break;
                          case 9: // SGML
                            System.out.print("SGML ");
                            System.out.println(et.encoding.o.toString());
                            break;
                          case 10: // XML
                            System.out.print("XML ");
                            System.out.println(new String((byte[])(et.encoding.o)));
                            break;
                          default:
                            System.out.println(et.encoding.o.toString());
                            break;
                         }
                      }
                      else
                        System.out.println("Unknown direct reference OID: "+et.direct_reference);
                      break;
                    case record_inline13_type.surrogatediagnostic_CID:
                      System.out.println("SurrogateDiagnostic");
                      break;
                    case record_inline13_type.startingfragment_CID:
                      System.out.println("StartingFragment");
                      break;
                    case record_inline13_type.intermediatefragment_CID:
                      System.out.println("IntermediateFragment");
                      break;
                    case record_inline13_type.finalfragment_CID:
                      System.out.println("FinalFragment");
                      break;
                    default:
                      System.out.println("Unknown Record type for NamePlusRecord");
                      break;
                  }
                }
                else
                {
                  System.out.println("Error... record ptr is null");
                }
            }
            break;
        case Records_type.nonsurrogatediagnostic_CID:
	    DefaultDiagFormat_type diag = (DefaultDiagFormat_type)r.o;
            System.out.println("    Non surrogate diagnostics : "+diag.condition);
	    if ( diag.addinfo != null )
	    {
              // addinfo is VisibleString in v2, InternationalString in V3
              System.out.println("Additional Info: "+diag.addinfo.o.toString());
	    }
            break;
        case Records_type.multiplenonsurdiagnostics_CID:
            System.out.println("    Multiple non surrogate diagnostics");
            break;
        default:
            System.err.println("    Unknown choice for records response : "+r.which);
            break;
      }
    }

    // if ( null != e.getPDU().presentResponse.otherInfo )
    //   System.out.println("  Has other information");
    System.out.println("");

  }

}

