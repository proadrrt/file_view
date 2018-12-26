package com.filepreview.controller;

import com.filepreview.model.FileModel;
import com.filepreview.service.DownloadNetFileService;
import com.filepreview.service.FileConventerService;
import com.filepreview.service.FileService;
import com.filepreview.util.FileUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by chicheng on 2017/12/28.
 */
@Controller
public class ConventerController {

    @Autowired
    private FileService fileService;

    @Autowired
    private DownloadNetFileService downloadNetFileService;

    @Autowired
    private FileConventerService fileConventerService;

    @Value("${tmp.root}")
    private String rootPath;

    @Value("${text.type}")
    private String textType;

    @Value("${img.type}")
    private String imgType;

    @Value("${office.type}")
    private String officeType;

    @Value("${compress.type}")
    private String compressType;

    @Value("${pdf.type}")
    private String pdfType;

    private Map<String, String> pptMap = new HashMap<>();

    /**
     * 文件转换：1、从url地址下载文件 2、转换文件
     * @param model
     * @param filePath
     * @param pc 有参代表pc端
     * @throws UnsupportedEncodingException
     * @return String
     */
    @RequestMapping("/fileConventer")
    public String fileConventer(String filePath, Model model, @RequestParam(value = "pc",defaultValue = "2") String pc, HttpServletRequest request)
            throws UnsupportedEncodingException {
        // 先去查询,如果存在,不需要转化文档,为找到有效安全的url编码,所以这里使用循环来判断当前文件是否存在
        FileModel oldFileModel = null;
        List<String> keys = this.fileService.findAllKeys();
        for (String key : keys) {
            FileModel tmp = this.fileService.findFileModelByHashCode(key);
            if (tmp != null && tmp.getOriginal().equals(filePath)) {
                oldFileModel = tmp;

                break;
            }
        }
        // 文件已下载，不需要转换
        if (oldFileModel != null) {
            return previewUrl(oldFileModel, model, request,pc);
        } else {
            FileModel fileModel = new FileModel();
            // 文件来源url
            fileModel.setOriginal(filePath);
            // 创建时间,使用毫秒数
            fileModel.setCreateMs(System.currentTimeMillis());
            // 文件有效时间 10分钟
            fileModel.setLimitMs(10 * 60 * 1000);
            // 文件新建 未下载状态
            fileModel.setState(FileModel.STATE_WXZ);
            // 下载文件
            this.downloadNetFileService.download(fileModel);
            // 转换文件
            this.fileConventerService.conventer(fileModel);
            // 文件展现到前端
            if (fileModel.getState() != FileModel.STATE_YZH) {
                throw new RuntimeException("convert fail.");
            }
            return previewUrl(fileModel, model, request,pc);
        }
    }

    /**
     * 获取重定向路径
     * @param fileModel
     * @param model
     * @param pc 有参代表pc端
     * @throws UnsupportedEncodingException
     * @return String
     */
    private String previewUrl(FileModel fileModel, Model model, HttpServletRequest request,String pc)
            throws UnsupportedEncodingException {
        StringBuffer previewUrl = new StringBuffer();
        previewUrl.append("/viewer/document/");
        // pathId确定预览文件
        previewUrl.append(fileModel.getPathId());
        previewUrl.append(File.separator);

        // 判断转换后的文件是否存在,不存在则跳到error页面
        File file = new File(rootPath + File.separator + fileModel.getPathId()
                + File.separator + "resource" + File.separator + fileModel.getConventedFileName());
        String subfix = FileUtil.getFileSufix(fileModel.getOriginalFile());
        model.addAttribute("pathId", fileModel.getPathId());
        model.addAttribute("fileType", subfix);
        model.addAttribute("pc",pc);
        if (file.exists()) {
            // 判断文件类型，不同的文件做不同的展示
            if (this.pdfType.contains(subfix.toLowerCase())) {
                return "html";
            } else if (this.textType.contains(subfix.toLowerCase())) {
                return "txt";
            } else if (this.imgType.contains(subfix.toLowerCase())) {
                return "picture";
            } else if (this.compressType.contains(subfix.toLowerCase())) {
                model.addAttribute("fileTree", fileModel.getFileTree());
                return "compress";
            } else if (this.officeType.contains(subfix.toLowerCase())) {
                if ("pptx".equalsIgnoreCase(subfix.toLowerCase()) ||
                        "ppt".equalsIgnoreCase(subfix.toLowerCase())) {
                    List<String> imgFiles = fileService.getImageFilesOfPPT(fileModel.getPathId());
                    String imgPaths = "";
                    for(String s : imgFiles) {
                        imgPaths +=(fileModel.getPathId() + "/resource/"
                                + s.substring(s.lastIndexOf("\\"), s.length()) + ",");
                    }
                    model.addAttribute("imgPaths", imgPaths);
                    return "ppt";
                } else {
                    return "office";
                }
            }
        } else {
            return "forward:/fileNotSupported";
        }
        return null;
    }

    /**
     * 获取预览文件
     * @param pathId
     * @param response
     * @param fileFullPath 此参数主要针对压缩文件,利用该参数获取解压后的文件
     * @return
     */
    @RequestMapping(value = "/viewer/document", method = RequestMethod.GET)
    @ResponseBody
    public void onlinePreview(String pathId, @RequestParam(required = false) String pc,String fileFullPath, HttpServletResponse response,HttpServletRequest request) throws IOException {
        /*if(pathId.indexOf("img") != -1) {
            System.out.println(pathId);
            String filePath = pptMap.get("pptKey");
            String fileUrl = rootPath + File.separator + filePath + File.separator + "resource" + File.separator + pathId;
            String i = fileUrl.substring(fileUrl.length()-1, fileUrl.length());
            fileUrl = fileUrl.substring(0, fileUrl.length()-1);
            if(Integer.valueOf(i) > 0) {
                fileUrl += i + ".html";
            } else {
                fileUrl += i + ".jpg";
            }
            FileInputStream is = new FileInputStream(new File(fileUrl));
            OutputStream os = response.getOutputStream();
            printFile(is, os);
        } else {*/
            // 根据pathId获取fileModel
        FileModel fileModel = this.fileService.findFileModelByHashCode(pathId);
        if (fileModel == null) {
            throw new RuntimeException("fileModel 不能为空");
        }
        if (fileModel.getState() != FileModel.STATE_YZH) {
            throw new RuntimeException("convert fail.");
        }

        // 得到转换后的文件地址
        String fileUrl = "";

        if (fileFullPath != null) {
            fileUrl = rootPath + File.separator + fileFullPath;
        } else {
            fileUrl = rootPath + File.separator + fileModel.getPathId() + File.separator + "resource" + File.separator + fileModel.getConventedFileName();
            /*if("ppt".equals(fileUrl.substring(fileUrl.length()-8, fileUrl.length()-5))) {
                pptMap.put("pptKey", fileModel.getPathId());
            }*/
        }
        //File file = new File(fileUrl);

        // 设置内容长度
        //response.setContentLength((int) file.length());

        // 内容配置中要转码,inline 浏览器支持的格式会在浏览器中打开,否则下载
        String fullFileName = new String(fileModel.getConventedFileName());
        //response.setHeader("Content-Disposition", "inline;fileName=\"" + fullFileName + "\"");

        // 设置content-type
        //response.setContentType(fileModel.getOriginalMIMEType());
        //response.setContentType("Content-Type=text/html");
        //FileInputStream is = new FileInputStream(new File(fileUrl));
        //OutputStream os = response.getOutputStream();
        //printFile(is, os);
        PdfToImage(fileUrl, request, response,pc,fullFileName);
        //PdfToImage(fileUrl);
    }

    @RequestMapping("aaa")
    public String test() {
        return "ppt";
    }


        public void PdfToImage(String pdfurl,HttpServletRequest request,HttpServletResponse response,String pc,String fullFileName){
            StringBuffer buffer = new StringBuffer();
            FileOutputStream fos;
            PDDocument document;
            File pdfFile;
            int size;
            BufferedImage image;
            FileOutputStream out;
            Long randStr = 0l;
            //PDF转换成HTML保存的文件夹
            String path = "E:/Xshell/1";

           String string=path.substring(0,path.lastIndexOf("/"));
            System.out.println(string);
            //删除文件
            delAllFile(path);
            delAllFile(string);

            File htmlsDir = new File(path);
            if(!htmlsDir.exists()){
                htmlsDir.mkdirs();
            }
            File htmlDir = new File(path+"/");
            if(!htmlDir.exists()){
                htmlDir.mkdirs();
            }
            try{
                //遍历处理pdf附件
                randStr = System.currentTimeMillis();
                buffer.append("<!doctype html>\r\n");
                buffer.append("<head>\r\n");
                buffer.append("<meta charset=\"UTF-8\">\r\n");
                buffer.append("<title>家谱文件预览</title>\r\n");
                buffer.append("</head>\r\n");
                //可以禁止鼠标右键
                //buffer.append("<body oncontextmenu = \"return false\"; style=\"background-color:gray;\">\r\n");
                //可以禁止鼠标右键 键盘ctrl shift alt
                buffer.append("<body onmousemove=/HideMenu()/ oncontextmenu=\"return false\" \n" +
                        "ondragstart=\"return false\" onselectstart =\"return false\" \n" +
                        "onselect=\"document.selection.empty()\" \n" +
                        "oncopy=\"document.selection.empty()\" onbeforecopy=\"return false\" \n" +
                        "onmouseup=\"document.selection.empty()\" style=\"background-color:gray;\">\r\n");
                buffer.append("<style>\r\n");
                // 2代表pc 其它  代表手机
                if("2".equals(pc)){
                    buffer.append("img {background-color:#fff; text-align:center;margin-left: auto;display: block;margin-right: auto; width:50%; max-width:100%;margin-top:6px;}\r\n");
                }else {
                buffer.append("img {background-color:#fff; text-align:center; width:100%; max-width:100%;margin-top:6px;}\r\n");
                }
                buffer.append("</style>\r\n");
                document = new PDDocument();
                //pdf附件
                pdfFile = new File(pdfurl);
                document = PDDocument.load(pdfFile, (String) null);
                size = document.getNumberOfPages();
                Long start = System.currentTimeMillis(), end = null;
                System.out.println("===>pdf : " + pdfFile.getName() +" , size : " + size);
                PDFRenderer reader = new PDFRenderer(document);
                for(int i=0 ; i < size; i++){
                    //image = new PDFRenderer(document).renderImageWithDPI(i,130,ImageType.RGB);
                    image = reader.renderImage(i, 1.5f);
                    //生成图片,保存位置
                    out = new FileOutputStream(path + "/"+ "image" + "_" + i + ".jpg");
                    //ImageIO.write(image, "png", out); //使用png的清晰度
                    ImageIO.write(image, "bmp", out); //使用bmp的清晰度
                    //将图片路径追加到网页文件里
                    //buffer.append("<img src=\"" + path +"/"+ "image" + "_" + i + ".jpg\"/>\r\n");
                    buffer.append("<img src=/jpgImg/image_"+i+".jpg>\r\n");
                    image = null; out.flush(); out.close();
                }

                reader = null;
                document.close();
                buffer.append("</body>\r\n");
                buffer.append("</html>");
                end = System.currentTimeMillis() - start;
                System.out.println("===> Reading pdf times: " + (end/1000));
                start = end = null;
                //生成网页文件
                fos = new FileOutputStream(path+randStr+".html");
                System.out.println(path+randStr+".html");
                fos.write(buffer.toString().getBytes());
                fos.flush(); fos.close();

                Document doc = Jsoup.parse(new String(buffer));
                /*Elements es =  doc.select("table");
                for (Element element : es) {
                    element.html("123");//将table的内容替换为123
                }
                for (Element element : es) {
                    System.out.println(element.html());
                }*/
                System.out.println(doc.outerHtml());
                response.setContentType("text/html;charset=utf-8");
                PrintWriter printWriter=response.getWriter();
                printWriter.println(doc.outerHtml());
            }catch(Exception e){
                System.out.println("===>Reader parse pdf to jpg error : " + e.getMessage());
                e.printStackTrace();
            }
    }

    //删除指定文件夹下所有文件
   //param path 文件夹完整绝对路径
    public boolean delAllFile(String path) {
        boolean flag = false;
        File file = new File(path);
        if (!file.exists()) {
            return flag;
        }
        if (!file.isDirectory()) {
            return flag;
        }
        String[] tempList = file.list();
        File temp = null;
        for (int i = 0; i < tempList.length; i++) {
            if (path.endsWith(File.separator)) {
                temp = new File(path + tempList[i]);
            } else {
                temp = new File(path + File.separator + tempList[i]);
            }
            if (temp.isFile()) {
                temp.delete();
            }
            if (temp.isDirectory()) {
                delAllFile(path + "/" + tempList[i]);//先删除文件夹里面的文件
                //delFolder(path + "/" + tempList[i]);//再删除空文件夹
                flag = true;
            }
        }
        return flag;
    }
}


