module cerialmasterclient.tests {
  requires org.junit.jupiter.api;
  requires com.guicedee.activitymaster.cerialmaster.client;
  requires com.guicedee.cerial;
  requires com.guicedee.guicedinjection;

  exports com.guicedee.activitymaster.cerialmaster.client.test;
  exports com.guicedee.activitymaster.cerialmaster.client.testimpl;
  opens com.guicedee.activitymaster.cerialmaster.client.test to org.junit.platform.commons;

  provides com.guicedee.activitymaster.cerialmaster.client.services.IComPortStatusChanged
      with com.guicedee.activitymaster.cerialmaster.client.testimpl.TestStatusListener;
}
