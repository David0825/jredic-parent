package com.jredic.command;

/**
 * The interface of redis commands.
 * See <a href="https://redis.io/commands">Redis Commands</a>.
 *
 * @author David.W
 */
public interface Command {

    /**
     * 获取命令。
     *
     * @return
     *      命令。
     */
    String getCommand();

}