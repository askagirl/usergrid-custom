<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
    pageEncoding="ISO-8859-1"%>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">
<html>
<head>
	<meta http-equiv="Content-Type" content="text/html; charset=ISO-8859-1">
	<title>Confirm Organization</title>
	<link rel="stylesheet" type="text/css" href="../../../css/styles.css" />
</head>
<body>

	<p>Your organization <c:out value="${it.organization.name}"/> has been successfully confirmed.
	You will received an email soon to let you know when you organization has been activated</p>

</body>
</html>