/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.tyrus.core;

import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.websocket.Decoder;
import javax.websocket.MessageHandler;
import javax.websocket.PongMessage;

/**
 * Manages registered {@link MessageHandler}s and checks whether the new ones may be registered.
 *
 * @author Stepan Kopriva (stepan.kopriva at oracle.com)
 * @see MessageHandler
 * @see javax.websocket.OnMessage
 */
class MessageHandlerManager {

    private static final List<Class<?>> WHOLE_TEXT_HANDLER_TYPES = Arrays.asList(String.class, Reader.class);
    private static final Class<?> PARTIAL_TEXT_HANDLER_TYPE = String.class;
    private static final List<Class<?>> WHOLE_BINARY_HANDLER_TYPES = Arrays.asList(ByteBuffer.class, InputStream.class, byte[].class);
    private static final List<Class<?>> PARTIAL_BINARY_HANDLER_TYPES = Arrays.asList(ByteBuffer.class, byte[].class);
    private static final Class<?> PONG_HANDLER_TYPE = PongMessage.class;

    private boolean textHandlerPresent = false;
    private boolean binaryHandlerPresent = false;
    private boolean pongHandlerPresent = false;
    private final Map<Class<?>, MessageHandler> registeredHandlers = new HashMap<Class<?>, MessageHandler>();
    private final List<Decoder> decoders;

    private Set<MessageHandler> messageHandlerCache;

    /**
     * Construct manager with no decoders.
     */
    MessageHandlerManager() {
        this(Collections.<Decoder>emptyList());
    }

    /**
     * Construct manager.
     *
     * @param decoders registered {@link Decoder}s.
     */
    MessageHandlerManager(List<Decoder> decoders) {
        this.decoders = decoders;
    }

    /**
     * Add {@link MessageHandler} to the manager.
     *
     * @param handler {@link MessageHandler} to be added to the manager.
     */
    public void addMessageHandler(MessageHandler handler) throws IllegalStateException {

        if (!(handler instanceof MessageHandler.Basic) && !(handler instanceof MessageHandler.Async)) {
            throwException("MessageHandler must implement MessageHandler.Basic or MessageHandler.Async.");
        }

        final Class<?> handlerClass = getHandlerType(handler);

        if (handler instanceof MessageHandler.Basic) { //WHOLE MESSAGE HANDLER
            if (WHOLE_TEXT_HANDLER_TYPES.contains(handlerClass)) { // text
                if (textHandlerPresent) {
                    throwException("Text MessageHandler already registered.");
                } else {
                    textHandlerPresent = true;
                }
            } else if (WHOLE_BINARY_HANDLER_TYPES.contains(handlerClass)) { // binary
                if (binaryHandlerPresent) {
                    throwException("Binary MessageHandler already registered.");
                } else {
                    binaryHandlerPresent = true;
                }
            } else if (PONG_HANDLER_TYPE == handlerClass) { // pong
                if (pongHandlerPresent) {
                    throwException("Pong MessageHander already registered.");
                } else {
                    pongHandlerPresent = true;
                }
            } else {
                boolean decoderExists = false;

                if (checkTextDecoders(handlerClass)) {//decodable text
                    if (textHandlerPresent) {
                        throwException("Text MessageHandler already registered.");
                    } else {
                        textHandlerPresent = true;
                        decoderExists = true;
                    }
                } else if (checkBinaryDecoders(handlerClass)) {//decodable binary
                    if (binaryHandlerPresent) {
                        throwException("Text MessageHandler already registered.");
                    } else {
                        binaryHandlerPresent = true;
                        decoderExists = true;
                    }
                }

                if (!decoderExists) {
                    throwException(String.format("Decoder for type: %s has not been registered.", handlerClass));
                }
            }
        } else { // PARTIAL MESSAGE HANDLER
            if (PARTIAL_TEXT_HANDLER_TYPE.equals(handlerClass)) { // text
                if (textHandlerPresent) {
                    throwException("Text MessageHandler already registered.");
                } else {
                    textHandlerPresent = true;
                }
            } else if (PARTIAL_BINARY_HANDLER_TYPES.contains(handlerClass)) { // binary
                if (binaryHandlerPresent) {
                    throwException("Binary MessageHandler already registered.");
                } else {
                    binaryHandlerPresent = true;
                }
            } else {
                throwException(String.format("Partial MessageHandler can't be of type: %s.", handlerClass));
            }
        }

        // map of all registered handlers
        if (registeredHandlers.containsKey(handlerClass)) {
            throwException(String.format("MessageHandler for type: %s already registered.", handlerClass));
        } else {
            registeredHandlers.put(handlerClass, handler);
        }

        messageHandlerCache = null;
    }

    private void throwException(String text) throws IllegalStateException {
        throw new IllegalStateException(text);
    }

    /**
     * Remove {@link MessageHandler} from the manager.
     *
     * @param handler handler which will be removed.
     */
    public void removeMessageHandler(MessageHandler handler) {
        boolean wasRegistered = false;
        Iterator<Map.Entry<Class<?>, MessageHandler>> iterator = registeredHandlers.entrySet().iterator();
        final Class<?> handlerClass = getHandlerType(handler);

        while (iterator.hasNext()) {
            final Map.Entry<Class<?>, MessageHandler> next = iterator.next();
            if (next.getValue().equals(handler)) {
                iterator.remove();
                messageHandlerCache = null;
                wasRegistered = true;
                break;
            }
        }

        if (!wasRegistered) {
            return;
        }

        if (handler instanceof MessageHandler.Basic) { //WHOLE MESSAGE HANDLER
            if (WHOLE_TEXT_HANDLER_TYPES.contains(handlerClass)) { // text
                textHandlerPresent = false;

            } else if (WHOLE_BINARY_HANDLER_TYPES.contains(handlerClass)) { // binary
                binaryHandlerPresent = false;

            } else if (PONG_HANDLER_TYPE == handlerClass) { // pong
                pongHandlerPresent = false;
            } else {
                if (checkTextDecoders(handlerClass)) {//decodable text
                    textHandlerPresent = false;

                } else if (checkBinaryDecoders(handlerClass)) {//decodable binary
                    binaryHandlerPresent = false;
                }
            }
        } else { // PARTIAL MESSAGE HANDLER
            if (PARTIAL_TEXT_HANDLER_TYPE.equals(handlerClass)) { // text
                textHandlerPresent = false;

            } else if (PARTIAL_BINARY_HANDLER_TYPES.contains(handlerClass)) { // binary
                binaryHandlerPresent = false;
            }
        }
    }

    /**
     * Get all successfully registered {@link MessageHandler}s.
     *
     * @return unmodifiable {@link Set} of registered {@link MessageHandler}s.
     */
    public Set<MessageHandler> getMessageHandlers() {
        if (messageHandlerCache == null) {
            messageHandlerCache = Collections.unmodifiableSet(new HashSet(registeredHandlers.values()));
        }

        return messageHandlerCache;
    }

    private Class<?> getHandlerType(MessageHandler handler) {
        Class<?> root;
        if (handler instanceof AsyncMessageHandler) {
            return ((AsyncMessageHandler) handler).getType();
        } else if (handler instanceof BasicMessageHandler) {
            return ((BasicMessageHandler) handler).getType();
        } else if (handler instanceof MessageHandler.Async) {
            root = MessageHandler.Async.class;
        } else if (handler instanceof MessageHandler.Basic) {
            root = MessageHandler.Basic.class;
        } else {
            throw new IllegalArgumentException(handler.getClass().getName()); // should never happen
        }
        Class<?> result = ReflectionHelper.getClassType(handler.getClass(), root);
        return result == null ? Object.class : result;
    }

    private boolean checkTextDecoders(Class<?> requiredType) {
        for (Decoder decoder : decoders) {
            if (decoder instanceof CoderWrapper && isTextDecoder((CoderWrapper) decoder)) {
                if (((CoderWrapper<Decoder>) decoder).getType().isAssignableFrom(requiredType)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean checkBinaryDecoders(Class<?> requiredType) {
        for (Decoder decoder : decoders) {
            if (decoder instanceof CoderWrapper && isBinaryDecoder((CoderWrapper) decoder)) {
                if (((CoderWrapper<Decoder>) decoder).getType().isAssignableFrom(requiredType)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isTextDecoder(CoderWrapper<Decoder> decoder) {
        return Decoder.Text.class.isAssignableFrom(decoder.getCoderClass()) || Decoder.TextStream.class.isAssignableFrom(decoder.getCoderClass());
    }

    private static boolean isBinaryDecoder(CoderWrapper<Decoder> decoder) {
        return Decoder.Binary.class.isAssignableFrom(decoder.getCoderClass()) || Decoder.BinaryStream.class.isAssignableFrom(decoder.getCoderClass());
    }

}
