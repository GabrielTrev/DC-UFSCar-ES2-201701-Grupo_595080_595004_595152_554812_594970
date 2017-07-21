// Baseado nos arquivos importer e importer de PDF
package org.jabref.logic.importer;

import org.jabref.logic.util.FileExtensions;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.BibtexEntryTypes;

//baseado em tutorial de como criar arquivo xls em Java
import  org.apache.poi.hssf.usermodel.HSSFSheet;
import  org.apache.poi.hssf.usermodel.HSSFWorkbook;
import  org.apache.poi.hssf.usermodel.HSSFRow;
import  org.apache.poi.hssf.usermodel.HSSFCell;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;



// Created by Joao Victor
//

public class XLSImporter extends Importer{

    @Override
    public String getName(){
        return "XLS Importer";
    }

    @Override
    public FileExtensions getExtension(){
        return FileExtension.XLS;
    }

    @Override
    public String getDescription (){
        return "Criacao de Arquivo XLS";
    }
    @Override
    public boolean isRecognizedFormat(BufferedReader reader){
        return true;
    }

    @Override
    public ParserResult importDatabase(BufferedReader input) throws IOException {
        return null;
    }

    Override
    public ParserResult importDatabase(Path filePath, Charset encoding) throws IOException{
        List<BibEntry> bibitems = new ArrayList<>();
        fileInputStream inputStream = new fileInputStream(new File(filePath.toString()));
        XSSFWorkbook workbook = new XSSFWorkbook(inputStream);

        //Posiciona na posição zero
        XSSFWorkbook sheet = workbook.getSheetAt(0);

        //Seta o numero da celula no momento
        int rowNum = sheet.getLastRowNum()+1;

        for (int i=0; i<rowNum; i++){
            XSSFRow row = sheet.getRow(i);
            BibEntry bibentry = new BibEntry();
            bibentry.setType(BibtexEntryTypes.BOOK);
            XSSFCell cell = row.getCell(0);
            bibentry.setField("year", cel.toString());
            cel = row.getCell(1);
            bibentry.setField ("Author", cel.toString());
            cel = row.getCell(2);
            bibentry.setField("title", cel.toString());
            bibitems.add(bibentry);
        }
        ParserResult parserResult = new ParserResult(bibitems);
        parserResult.getMetaData().setEncoding(encoding);
        parserResult.setFile(filePath.toFile());
        return parserResult;
    }



}
