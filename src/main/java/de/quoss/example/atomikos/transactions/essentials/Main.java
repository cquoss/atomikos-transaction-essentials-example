package de.quoss.example.atomikos.transactions.essentials;

import com.atomikos.icatch.jta.UserTransactionManager;
import com.atomikos.jdbc.AtomikosDataSourceBean;
import com.atomikos.jms.AtomikosConnectionFactoryBean;
import org.apache.activemq.artemis.jms.client.ActiveMQXAConnectionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.ConnectionFactory;
import javax.jms.JMSConsumer;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.sql.DataSource;
import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private void run() throws HeuristicMixedException, HeuristicRollbackException, JMSException, RollbackException, NotSupportedException, SQLException, SystemException {

        // Set up h2 xa data source
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setUser("sa");
        dataSource.setPassword("");
        dataSource.setURL("jdbc:h2:~/test");

        // set up active mq artemis xa connection factory
        ActiveMQXAConnectionFactory acf = new ActiveMQXAConnectionFactory();

        // Atomikos implementations
        UserTransactionManager utm = new UserTransactionManager();
        AtomikosDataSourceBean adsb = new AtomikosDataSourceBean();
        adsb.setXaDataSource(dataSource);
        adsb.setUniqueResourceName("atomikosJdbc");
        AtomikosConnectionFactoryBean acfb = new AtomikosConnectionFactoryBean();
        acfb.setXaConnectionFactory(acf);
        acfb.setUniqueResourceName("atomikosJms");

        // Standard interfaces
        TransactionManager tm = utm;
        DataSource ds = adsb;
        ConnectionFactory cf = acfb;

        // init
        utm.init();

        // begin transaction, timout 15 minutes
        tm.setTransactionTimeout(15 * 60);
        tm.begin();

        // receive from jms (to-queue) and insert into database (customer table)
        try (Connection dbConnection = ds.getConnection();
             Statement statement = dbConnection.createStatement();
             javax.jms.Connection jmsConnection = cf.createConnection();
             Session session = jmsConnection.createSession();
             MessageConsumer consumer = session.createConsumer(session.createQueue("to-queue"))) {

            // drop table if exists
            String sql = "drop table messages if exists";
            statement.execute(sql);
            // create table
            sql = "create table messages( id integer primary key, message_id varchar(50) )";
            statement.execute(sql);

            // consume all messages, 10 minute timeout
            Message message = consumer.receive(10 * 60 * 1000L);
            while (message != null) {

                // get id and message id from jms
                String messsageId = message.getJMSMessageID();
                int id = fetchIdFromBody(message.getBody(Object.class));

                // insert message
                sql = String.format("insert into customer values ( %s, '%s' )", id, messsageId);
                statement.execute(sql);

                // fetch next message
                message = consumer.receive(10 * 60 * 1000L);

            }

        } catch (JMSException | SQLException e) {

            // roll back user transaction on error
            tm.rollback();
            throw e;

        } finally {

            // commit transaction if still active
            Transaction tx = tm.getTransaction();
            if (tx != null && tx.getStatus() == Status.STATUS_ACTIVE) {
                tm.commit();
            }

        }

        // shut down
        adsb.close();
        utm.close();

    }

    private int fetchIdFromBody(final Object o) {
        int result = 0;
        try {
            result = Integer.parseInt("" + o);
        } catch (NumberFormatException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
        return result;
    }

    public static void main(final String[] args) {
        try {
            new Main().run();
        } catch (HeuristicMixedException | HeuristicRollbackException | JMSException | NotSupportedException | RollbackException | SQLException | SystemException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            System.exit(1);
        }
    }

}
