#Spray/Akka HTTP implementation of ListenUp service 

The app uses simple Akka-HTTP actor system that for both querying external services
and routing incoming requests.

The following assumptions were made

1. Data in Play and Friends services is likely to change as time passes (
    users listen to more tracks, new users are registered) 
2. We can have some grace period for our data (i.e. batch requests are not aggregated on each call, insread we check 
    when the data was mined and if it is within given timeframes we just return the value).
3. There is a possibility that some data is lost while querying external services, in this case we 
    make several tries, but we can start making our data available for querying
    when we know that its consistency above certain threshold: 0.9 means that out of all users in lists returned by plays 
    and friends services we have full stats for at least 90% of users.
    
## Running service
It is an sbt project, the urls and ports for external services are specified in `application.conf` file. It was tested with
the provided services on localhost (ports 8000 & 8001 for external services, port 8005 ListenUp)

The service can be started by executing the following from project's root directory
    
    sbt run