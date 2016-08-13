package com.zendesk.maxwell;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeoutException;
import com.djdch.log4j.StaticShutdownCallbackRegistry;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zendesk.maxwell.bootstrap.AbstractBootstrapper;
import com.zendesk.maxwell.producer.AbstractProducer;
import com.zendesk.maxwell.schema.Schema;
import com.zendesk.maxwell.schema.SchemaCapturer;
import com.zendesk.maxwell.schema.MysqlSchemaStore;
import com.zendesk.maxwell.schema.SchemaStoreSchema;
import com.zendesk.maxwell.schema.ddl.InvalidSchemaError;

public class Maxwell {
	private MaxwellConfig config;
	private MaxwellContext context;
	static final Logger LOGGER = LoggerFactory.getLogger(Maxwell.class);

	protected BinlogPosition getInitialPosition() throws SQLException {
		BinlogPosition initial = this.context.getInitialPosition();
		if ( initial == null ) {
			Pair<Long, Long> recoveryInfo = this.context.getRecoveryInfo();

			if ( recoveryInfo != null ) {
				MaxwellMasterRecovery masterRecovery = new MaxwellMasterRecovery(this.context, recoveryInfo.getLeft(), recoveryInfo.getRight());
				initial = masterRecovery.recover();
			}
		}

		if ( initial == null ) {
			try ( Connection c = context.getReplicationConnection() ) {
				initial = BinlogPosition.capture(c);
			}
		}
		return initial;
	}

	private void run(String[] argv) throws Exception {
		this.config = new MaxwellConfig(argv);

		if ( this.config.log_level != null )
			MaxwellLogging.setLevel(this.config.log_level);

		this.context = new MaxwellContext(this.config);

		this.context.probeConnections();


 		BinlogPosition initialPosition = null;
		try ( Connection connection = this.context.getReplicationConnection();
			  Connection rawConnection = this.context.getRawMaxwellConnection() ) {
			MaxwellMysqlStatus.ensureReplicationMysqlState(connection);
			MaxwellMysqlStatus.ensureMaxwellMysqlState(rawConnection);

			SchemaStoreSchema.ensureMaxwellSchema(rawConnection, this.config.databaseName);

			try ( Connection schemaConnection = this.context.getMaxwellConnection() ) {
				SchemaStoreSchema.upgradeSchemaStoreSchema(schemaConnection);
			}

			String producerClass = this.context.getProducer().getClass().getSimpleName();
 			initialPosition = getInitialPosition();
			LOGGER.info("Maxwell is booting (" + producerClass + "), starting at " + initialPosition);
		} catch ( SQLException e ) {
			LOGGER.error("SQLException: " + e.getLocalizedMessage());
			LOGGER.error(e.getLocalizedMessage());
			return;
		}

		AbstractProducer producer = this.context.getProducer();
		AbstractBootstrapper bootstrapper = this.context.getBootstrapper();

		MysqlSchemaStore mysqlSchemaStore = new MysqlSchemaStore(this.context);
		final MaxwellReplicator p = new MaxwellReplicator(mysqlSchemaStore, producer, bootstrapper, this.context, initialPosition);

		bootstrapper.resume(producer, p);

		p.setFilter(context.getFilter());

		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				try {
					p.stopLoop();
				} catch (TimeoutException e) {
					System.err.println("Timed out trying to shutdown maxwell parser thread.");
				}
				context.terminate();
				StaticShutdownCallbackRegistry.invoke();
			}
		});

		this.context.start();
		p.runLoop();

	}

	public static void main(String[] args) {
		try {
			new Maxwell().run(args);
		} catch ( Exception e ) {
			e.printStackTrace();
			System.exit(1);
		}
	}
}
