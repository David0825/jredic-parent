package com.jredic.network;

import com.jredic.network.protocol.data.*;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.CharsetUtil;
import java.util.ArrayList;
import java.util.List;

/**
 * @author David.W
 */
public class RESPDecoder extends ByteToMessageDecoder {

    //current status
    private int bulkStringsHeader = -1;

    private int arraysHeader = -1;

    private List<Data> arrayElements = new ArrayList<>();

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Data data = decodeData(in);
        if(data != null){
            out.add(data);
        }
    }

    private Data decodeData(ByteBuf in) {
        //decode type
        if(!in.isReadable(RESPContants.DATA_TYPE_LENGTH)){
            return null;
        }
        DataType type = DataType.valueOf(in.readByte());
        switch (type){
            case SIMPLE_STRINGS:
                return decodeSimpleStringsData(in);
            case ERRORS:
                return decodeErrorsData(in);
            case INTEGERS:
                return decodeIntegersData(in);
            case BULK_STRINGS:
                return decodeBulkStringsData(in);
            case ARRAYS:
                return decodeArraysData(in);
            default:
                //never here
                throw new RESPException("Unknown Redis DataType: " + type);
        }
    }

    private Data decodeArraysData(ByteBuf in) {
        if(!in.isReadable(RESPContants.REDIS_DATA_ARRAY_MIN_LENGTH)){
            return null;
        }
        int crlfIndex = findCRLF(in);
        if(crlfIndex == -1){
            return null;
        }
        //case null arrays '$-1\r\n'
        if((crlfIndex - in.readerIndex()) == 2 &&
                in.getShort(in.readerIndex()) == RESPContants.NULL_DATA_LENGTH){
            //skip 4 bytes
            in.skipBytes(4);
            return ArraysData.getNullArray();
        }else{
            if(arraysHeader == -1){
                //read header
                if(crlfIndex - in.readerIndex() > RESPContants.POSITIVE_LONG_MAX_LENGTH){
                    throw new RESPException("header of redis array data length exceed " + RESPContants.POSITIVE_LONG_MAX_LENGTH);
                }
                ByteBuf headerBytes = in.readSlice(crlfIndex - in.readerIndex());
                String header = headerBytes.toString(CharsetUtil.UTF_8);
                try{
                    arraysHeader = Integer.parseInt(header);
                    //skip \r\n
                    in.skipBytes(RESPContants.CRLF_LENGTH);
                } catch (NumberFormatException e){
                    throw new RESPException("can't parse [" + header + "] to int value");
                }
            }
            if(arraysHeader == 0){
                //case empty arrays
                resetArraysStatus();
                return ArraysData.getEmptyArray();
            }else{
                int leftElementSize = arraysHeader;
                if(arrayElements != null && arrayElements.size() > 0){
                    leftElementSize -= arrayElements.size();
                }
                while(leftElementSize > 0){
                    Data data = decodeData(in);
                    if(data == null){
                        return null;
                    }
                    arrayElements.add(data);
                    leftElementSize--;
                }
                List<Data> elements = new ArrayList<>(arrayElements.size());
                elements.addAll(arrayElements);
                resetArraysStatus();
                return new ArraysData(elements);
            }
        }

    }

    private void resetArraysStatus() {
        this.arraysHeader = -1;
        this.arrayElements = new ArrayList<>();
    }

    private Data decodeBulkStringsData(ByteBuf in) {
        if(!in.isReadable(RESPContants.REDIS_DATA_BULK_STRING_MIN_LENGTH)){
            return null;
        }
        int crlfIndex = findCRLF(in);
        if(crlfIndex == -1){
            return null;
        }
        //case null bulk strings '$-1\r\n'
        if((crlfIndex - in.readerIndex()) == 2 &&
                in.getShort(in.readerIndex()) == RESPContants.NULL_DATA_LENGTH){
            //skip 4 bytes
            in.skipBytes(4);
            return BulkStringsData.getNullBulkString();
        }else{
            if(bulkStringsHeader == -1){
                //read header
                if(crlfIndex - in.readerIndex() > RESPContants.POSITIVE_LONG_MAX_LENGTH){
                    throw new RESPException("header of redis bulk string data length exceed " + RESPContants.POSITIVE_LONG_MAX_LENGTH);
                }
                ByteBuf headerBytes = in.readSlice(crlfIndex - in.readerIndex());
                String header = headerBytes.toString(CharsetUtil.UTF_8);
                try{
                    bulkStringsHeader = Integer.parseInt(header);
                } catch (NumberFormatException e){
                    throw new RESPException("can't parse [" + header + "] to int value");
                }
            }
            if(bulkStringsHeader == 0){
                //case empty bulk strings
                //skip 4 bytes
                if(!in.isReadable(4)){
                    return null;
                }
                in.skipBytes(4);
                resetBulkStringsHeader();
                return BulkStringsData.getEmptyBulkString();
            }else{
                //skip \r\n
                in.skipBytes(RESPContants.CRLF_LENGTH);
                //read content
                if(!in.isReadable(bulkStringsHeader)){
                    return null;
                }
                ByteBuf contentBytes = in.readSlice(bulkStringsHeader).retain();
                String content = contentBytes.toString(CharsetUtil.UTF_8);
                //skip \r\n
                in.skipBytes(RESPContants.CRLF_LENGTH);
                resetBulkStringsHeader();
                return new BulkStringsData(content);
            }
        }
    }

    private void resetBulkStringsHeader() {
        this.bulkStringsHeader = -1;
    }

    private Data decodeIntegersData(ByteBuf in) {
        if(!in.isReadable(RESPContants.REDIS_DATA_INTEGER_MIN_LENGTH)){
            return null;
        }
        int crlfIndex = findCRLF(in);
        if(crlfIndex == -1){
            return null;
        }
        int maxIntegersLength = RESPContants.POSITIVE_LONG_MAX_LENGTH;
        //if the first byte is sign
        if(in.getByte(in.readerIndex()) == '-'){
            maxIntegersLength += 1;
        }
        if(crlfIndex - in.readerIndex() > maxIntegersLength){
            throw new RESPException("redis integers data length exceed " + maxIntegersLength);
        }
        ByteBuf byteBuf = in.readSlice(crlfIndex - in.readerIndex()).retain();
        String str = byteBuf.toString(CharsetUtil.UTF_8);
        try{
            long value = Long.parseLong(str);
            //skip \r\n
            in.skipBytes(RESPContants.CRLF_LENGTH);
            return new IntegersData(value);
        } catch (NumberFormatException e){
            throw new RESPException("can't parse [" + str + "] to long value");
        }

    }

    private Data decodeErrorsData(ByteBuf in) {
        if(!in.isReadable(RESPContants.REDIS_DATA_STRING_MIN_LENGTH)){
            return null;
        }
        int crlfIndex = findCRLF(in);
        if(crlfIndex == -1){
            return null;
        }
        if(crlfIndex - in.readerIndex() > RESPContants.REDIS_DATA_MAX_INLINE_LENGTH){
            throw new RESPException("redis data length exceed " + RESPContants.REDIS_DATA_MAX_INLINE_LENGTH);
        }
        ByteBuf byteBuf = in.readSlice(crlfIndex - in.readerIndex()).retain();
        //skip \r\n
        in.skipBytes(RESPContants.CRLF_LENGTH);
        return new ErrorsData(byteBuf.toString(CharsetUtil.UTF_8));
    }

    private Data decodeSimpleStringsData(ByteBuf in) {
        if(!in.isReadable(RESPContants.REDIS_DATA_STRING_MIN_LENGTH)){
            return null;
        }
        int crlfIndex = findCRLF(in);
        if(crlfIndex == -1){
            return null;
        }
        if(crlfIndex - in.readerIndex() > RESPContants.REDIS_DATA_MAX_INLINE_LENGTH){
            throw new RESPException("redis data length exceed " + RESPContants.REDIS_DATA_MAX_INLINE_LENGTH);
        }
        ByteBuf byteBuf = in.readSlice(crlfIndex - in.readerIndex()).retain();
        //skip \r\n
        in.skipBytes(RESPContants.CRLF_LENGTH);
        return new SimpleStringsData(byteBuf.toString(CharsetUtil.UTF_8));
    }

    private int findCRLF(ByteBuf in) {
        int fromIndex = in.readerIndex();
        int toIndex = in.writerIndex();
        for(int i=fromIndex; i<toIndex; i++){
            if(in.getByte(i) == '\r'
                    && in.getByte(i+1) == '\n'){
                return i;
            }
        }
        return -1;
    }

}