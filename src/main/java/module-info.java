import com.guicedee.activitymaster.cerialmaster.client.services.*;

module com.guicedee.activitymaster.cerialmaster.client {

  requires org.apache.logging.log4j.core;
  requires org.slf4j;

  requires org.apache.commons.io;

  requires transitive com.jwebmp.core.base.angular.client;

  requires transitive com.guicedee.cerial;
  requires com.guicedee.activitymaster.fsdm.client;
  requires org.hibernate.reactive;
  requires static lombok;

  opens com.guicedee.activitymaster.cerialmaster.client to com.google.guice, tools.jackson.databind;
  opens com.guicedee.activitymaster.cerialmaster.client.services to com.google.guice, tools.jackson.databind;
  opens com.guicedee.activitymaster.cerialmaster.client.dto to com.google.guice, tools.jackson.databind;

  exports com.guicedee.activitymaster.cerialmaster.client;
  exports com.guicedee.activitymaster.cerialmaster.client.services;
  exports com.guicedee.activitymaster.cerialmaster.client.dto;

  uses IReceiveMessage;
  uses IErrorReceiveMessage;
  uses ITerminalReceiveMessage;
  uses ICleanReceivedMessage;
  uses IComPortStatusChanged;

}