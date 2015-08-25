# RC

Reliable Connect application creates SSH tunnels according to configuration file and keeps them alive forever.
It tries to reconnect upon any occurring errors

Visit: [vastline.net](http://vastline.net "Vastline")

# Build

<code>mvn clean install</code>

The result is: <code>target/rc.jar</code>

# Usage

Create <code>rc.conf</code> configuration file. Example:

<pre>
# Single configuration can have multiple sessions.
# Each session starts with the connection details followed by forward rules.
#
# SSH_USER@SSH_HOSTNAME_OR_IP:SSH_PORT PATH/TO/PRIVATE_SSH_KEY
# L LOCAL_PORT_TO_LISTEN:REMOTE_TARGET_HOST:REMOTE_TARGET_PORT
# R REMOTE_PORT_TO_LISTEN:LOCAL_TARGET_HOST:LOCAL_TARGET_PORT

bob@example1.com:22 .ssh/id_dsa
L 10080:example-server1.com:80
L 10022:example-server1.com:22
R 10080:localhost:80
R 10022:localhost:22

alice@home-server.com:443 id_rsa
L 13389:home-pc:3389
</pre>

Place this file near <code>rc.jar</code>.

Run application:

<code>java -jar rc.jar</code>

or

<code>java -jar rc.jar path/to/conf.file</code>
